// Cadence: two-phase GPU occlusion culling of terrain sections for the shader pack's main terrain pass (Hi-Z).
//
// The pass draws the same indirect commands twice. Before it, cull_a sets each command's instance count to 1 when its
// section was visible last frame (a stamp in a 64x32x64 section grid), else 0: phase 1 draws those. Mid-pass (the pass
// is suspended, its depth stored), the depth is reduced into a max-depth pyramid (pyr0/pyrN) and cull_b tests every
// command's section box against it: a box whose nearest depth is behind the farthest depth everywhere it covers is
// hidden. Visible sections get this frame's stamp; commands drawn in phase 1 get 0, the newly visible ones 1: phase 2
// draws those. A section is only skipped when it is behind depth already drawn this frame: nothing pops in.
#import <Metal/Metal.h>
#import "mcmetal.h"

id<MTLCommandBuffer> mc_frame_cmd(Enc *enc);

static NSString *const CULL_SRC = @
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct Draw { uint indexCount; uint instanceCount; uint firstIndex; int baseVertex; uint baseInstance; };\n"
"struct Info { int x; int y; int z; float vis; };\n"
"struct P { float4x4 vp; int4 cam; float4 frac; uint frame; uint w; uint h; uint mips; };\n"
"inline uint gkey(int3 s) { return uint(s.x & 63) | (uint(s.y & 31) << 6) | (uint(s.z & 63) << 11); }\n"
"kernel void cull_a(device Draw *draws [[buffer(0)]], const device Info *infos [[buffer(1)]], device uint *grid [[buffer(2)]],\n"
"    constant P &p [[buffer(3)]], constant uint &count [[buffer(4)]], uint i [[thread_position_in_grid]]) {\n"
"  if (i >= count) return;\n"
"  Info s = infos[draws[i].baseInstance];\n"
"  uint stamp = grid[gkey(int3(s.x, s.y, s.z) >> 4)];\n"
"  draws[i].instanceCount = stamp != 0 && stamp + 1 >= p.frame ? 1u : 0u;\n"
"}\n"
"kernel void pyr0(depth2d<float, access::read> d [[texture(0)]], texture2d<float, access::write> o [[texture(1)]],\n"
"    uint2 g [[thread_position_in_grid]]) {\n"
"  uint W = d.get_width(), H = d.get_height(), ow = o.get_width(), oh = o.get_height();\n"
"  if (g.x >= ow || g.y >= oh) return;\n"
"  uint x0 = g.x * 2, y0 = g.y * 2;\n"
"  uint x1 = g.x == ow - 1 ? W - 1 : min(x0 + 1, W - 1), y1 = g.y == oh - 1 ? H - 1 : min(y0 + 1, H - 1);\n"
"  float m = 0.0;\n"
"  for (uint y = y0; y <= y1; y++) for (uint x = x0; x <= x1; x++) m = max(m, d.read(uint2(x, y)));\n"
"  o.write(float4(m), g);\n"
"}\n"
"kernel void pyrN(texture2d<float, access::read> s [[texture(0)]], texture2d<float, access::write> o [[texture(1)]],\n"
"    uint2 g [[thread_position_in_grid]]) {\n"
"  uint W = s.get_width(), H = s.get_height(), ow = o.get_width(), oh = o.get_height();\n"
"  if (g.x >= ow || g.y >= oh) return;\n"
"  uint x0 = g.x * 2, y0 = g.y * 2;\n"
"  uint x1 = g.x == ow - 1 ? W - 1 : min(x0 + 1, W - 1), y1 = g.y == oh - 1 ? H - 1 : min(y0 + 1, H - 1);\n"
"  float m = 0.0;\n"
"  for (uint y = y0; y <= y1; y++) for (uint x = x0; x <= x1; x++) m = max(m, s.read(uint2(x, y)).r);\n"
"  o.write(float4(m), g);\n"
"}\n"
// Visible unless provably behind: a corner at or behind the eye, or anything odd, counts as visible.
"inline bool boxVisible(float3 lo, float3 hi, constant P &p, array<texture2d<float, access::read>, 14> lv) {\n"
// p.frac.w: bit 0 reversed depth (near 1, far 0: the vanilla pass in 26.x), bit 1 clip z already 0..1. Pack passes: 0 (GL).
"  uint mode = uint(p.frac.w); bool rev = (mode & 1u) != 0u; bool z01 = (mode & 2u) != 0u;\n"
"  float nearZ = rev ? 0.0 : 1.0; float2 mn = float2(1e9), mx = float2(-1e9);\n"
"  for (uint c = 0; c < 8; c++) {\n"
"    float3 v = float3((c & 1) ? hi.x : lo.x, (c & 2) ? hi.y : lo.y, (c & 4) ? hi.z : lo.z);\n"
"    float4 clip = p.vp * float4(v, 1.0);\n"
"    if (!(clip.w > 0.05)) return true;\n"
"    float3 ndc = clip.xyz / clip.w;\n"
"    float d = z01 ? ndc.z : ndc.z * 0.5 + 0.5;\n"
"    nearZ = rev ? max(nearZ, d) : min(nearZ, d);\n"
"    mn = min(mn, ndc.xy); mx = max(mx, ndc.xy);\n"
"  }\n"
"  if (mx.x < -1.0 || mn.x > 1.0 || mx.y < -1.0 || mn.y > 1.0) return false;\n"
"  if (rev ? nearZ >= 1.0 : nearZ <= 0.0) return true;\n"
"  mn = clamp(mn, -1.0, 1.0); mx = clamp(mx, -1.0, 1.0);\n"
"  float2 u0 = mn * 0.5 + 0.5, u1 = mx * 0.5 + 0.5;\n"
"  int px0 = int(floor(u0.x * float(p.w))) - 1, py0 = int(floor(u0.y * float(p.h))) - 1;\n"
"  int px1 = int(ceil(u1.x * float(p.w))) + 1, py1 = int(ceil(u1.y * float(p.h))) + 1;\n"
"  px0 = max(px0, 0); py0 = max(py0, 0); px1 = min(px1, int(p.w) - 1); py1 = min(py1, int(p.h) - 1);\n"
// mip L of the pyramid covers 2^(L+1) depth pixels per texel; pick the level where the rectangle spans at most 2x2 texels
"  int span = max(px1 - px0, py1 - py0) + 1;\n"
"  uint L = 0; while (L + 1 < p.mips && (span >> (L + 1)) > 1) L++;\n"
"  int s = int(L) + 1;\n"
"  int tx0 = px0 >> s, ty0 = py0 >> s, tx1 = px1 >> s, ty1 = py1 >> s;\n"
"  texture2d<float, access::read> t = lv[L];\n"
"  int tw = int(t.get_width()) - 1, th = int(t.get_height()) - 1;\n"
// the farthest depth already drawn over the rectangle: the largest value, or with reversed depth the smallest
"  float far = rev ? 1.0 : 0.0;\n"
"  for (int y = ty0; y <= ty1; y++) for (int x = tx0; x <= tx1; x++) {\n"
"    float v = t.read(uint2(min(x, tw), min(y, th))).r; far = rev ? min(far, v) : max(far, v);\n"
"  }\n"
"  return rev ? nearZ >= far - 1e-6 : nearZ <= far + 1e-6;\n"
"}\n"
"kernel void cull_b(device Draw *draws [[buffer(0)]], const device Info *infos [[buffer(1)]], device uint *grid [[buffer(2)]],\n"
"    constant P &p [[buffer(3)]], constant uint &count [[buffer(4)]], array<texture2d<float, access::read>, 14> lv [[texture(0)]],\n"
"    uint i [[thread_position_in_grid]]) {\n"
"  if (i >= count) return;\n"
"  Draw d = draws[i];\n"
"  Info s = infos[d.baseInstance];\n"
"  int3 o = int3(s.x, s.y, s.z);\n"
"  float3 lo = float3(o - p.cam.xyz) - p.frac.xyz;\n"
"  bool visible = boxVisible(lo, lo + 16.0, p, lv);\n"
"  if (visible) grid[gkey(o >> 4)] = p.frame;\n"
"  draws[i].instanceCount = visible && d.instanceCount == 0 ? 1u : 0u;\n"
"}\n"
// Every command drawn (a frame that can't be culled after all: the draws phase 1 skipped).
"kernel void cull_one(device Draw *draws [[buffer(0)]], constant uint &count [[buffer(4)]], uint i [[thread_position_in_grid]]) {\n"
"  if (i >= count) return;\n"
"  draws[i].instanceCount = 1u - min(draws[i].instanceCount, 1u);\n"
"}\n"
// Translucent layers, after the opaque terrain: drawn only where not behind it (one phase, this frame's pyramid).
"kernel void cull_t(device Draw *draws [[buffer(0)]], const device Info *infos [[buffer(1)]], device uint *grid [[buffer(2)]],\n"
"    constant P &p [[buffer(3)]], constant uint &count [[buffer(4)]], array<texture2d<float, access::read>, 14> lv [[texture(0)]],\n"
"    uint i [[thread_position_in_grid]]) {\n"
"  if (i >= count) return;\n"
"  Info s = infos[draws[i].baseInstance];\n"
"  int3 o = int3(s.x, s.y, s.z);\n"
"  float3 lo = float3(o - p.cam.xyz) - p.frac.xyz;\n"
"  draws[i].instanceCount = boxVisible(lo, lo + 16.0, p, lv) ? 1u : 0u;\n"
"}\n"
// Cadence: the same pyramid for reversed depth (the vanilla pass): each texel keeps the farthest, i.e. smallest, depth
"kernel void pyr0r(depth2d<float, access::read> d [[texture(0)]], texture2d<float, access::write> o [[texture(1)]],\n"
"    uint2 g [[thread_position_in_grid]]) {\n"
"  uint W = d.get_width(), H = d.get_height(), ow = o.get_width(), oh = o.get_height();\n"
"  if (g.x >= ow || g.y >= oh) return;\n"
"  uint x0 = g.x * 2, y0 = g.y * 2;\n"
"  uint x1 = g.x == ow - 1 ? W - 1 : min(x0 + 1, W - 1), y1 = g.y == oh - 1 ? H - 1 : min(y0 + 1, H - 1);\n"
"  float m = 1.0;\n"
"  for (uint y = y0; y <= y1; y++) for (uint x = x0; x <= x1; x++) m = min(m, d.read(uint2(x, y)));\n"
"  o.write(float4(m), g);\n"
"}\n"
"kernel void pyrNr(texture2d<float, access::read> s [[texture(0)]], texture2d<float, access::write> o [[texture(1)]],\n"
"    uint2 g [[thread_position_in_grid]]) {\n"
"  uint W = s.get_width(), H = s.get_height(), ow = o.get_width(), oh = o.get_height();\n"
"  if (g.x >= ow || g.y >= oh) return;\n"
"  uint x0 = g.x * 2, y0 = g.y * 2;\n"
"  uint x1 = g.x == ow - 1 ? W - 1 : min(x0 + 1, W - 1), y1 = g.y == oh - 1 ? H - 1 : min(y0 + 1, H - 1);\n"
"  float m = 1.0;\n"
"  for (uint y = y0; y <= y1; y++) for (uint x = x0; x <= x1; x++) m = min(m, s.read(uint2(x, y)).r);\n"
"  o.write(float4(m), g);\n"
"}\n";

typedef struct {
	float vp[16];
	int cam[4];
	float frac[4];
	uint32_t frame, w, h, mips;
} CullParams;

typedef struct {
	id<MTLComputePipelineState> a, p0, pn, b, t, one, p0r, pnr;
	id<MTLBuffer> grid;
	id<MTLTexture> pyramid;
	id<MTLTexture> levels[14];
	uint32_t mips, pw, ph, depthW, depthH;
	CullParams params;
} CullState;

// The state (pipelines, section grid) for this device, or NULL when the kernels can't be built (the caller then draws normally).
void *mc_cull_new(Ctx *ctx) {
	@autoreleasepool {
		NSError *err = nil;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:CULL_SRC options:nil error:&err];
		if (!lib) {
			NSLog(@"cadence-metal: Cadence occlusion culling kernels failed: %@", err);
			return NULL;
		}
		CullState *st = calloc(1, sizeof(CullState));
		const char *names[] = {"cull_a", "pyr0", "pyrN", "cull_b", "cull_t", "cull_one", "pyr0r", "pyrNr"};
		id<MTLComputePipelineState> *slots[] = {&st->a, &st->p0, &st->pn, &st->b, &st->t, &st->one, &st->p0r, &st->pnr};
		for (int i = 0; i < 8; i++) {
			id<MTLFunction> f = [lib newFunctionWithName:[NSString stringWithUTF8String:names[i]]];
			*slots[i] = f ? [ctx->device newComputePipelineStateWithFunction:f error:&err] : nil;
			[f release];
			if (!*slots[i]) {
				NSLog(@"cadence-metal: Cadence occlusion culling kernel %s failed: %@", names[i], err);
				[lib release];
				free(st);
				return NULL;
			}
		}
		[lib release];
		st->grid = [ctx->device newBufferWithLength:64 * 32 * 64 * sizeof(uint32_t) options:MTLResourceStorageModePrivate];
		// zero it once (stamps start at 0 = never visible)
		id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
		id<MTLBlitCommandEncoder> bl = [cb blitCommandEncoder];
		[bl fillBuffer:st->grid range:NSMakeRange(0, st->grid.length) value:0];
		[bl endEncoding];
		[cb commit];
		return st;
	}
}

static void ensurePyramid(Ctx *ctx, CullState *st, uint32_t w, uint32_t h) {
	if (st->pyramid && st->depthW == w && st->depthH == h) return;
	for (uint32_t i = 0; i < st->mips; i++) [st->levels[i] release];
	[st->pyramid release];
	uint32_t pw = (w + 1) / 2, ph = (h + 1) / 2, big = pw > ph ? pw : ph, mips = 1;
	// Metal's full chain: floor(log2(largest side)) + 1 levels (each level halves, rounding down)
	while ((big >> mips) > 0 && mips < 14) mips++;
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:pw height:ph mipmapped:YES];
	d.mipmapLevelCount = mips;
	d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsagePixelFormatView;
	d.storageMode = MTLStorageModePrivate;
	st->pyramid = [ctx->device newTextureWithDescriptor:d];
	for (uint32_t i = 0; i < mips; i++)
		st->levels[i] = [st->pyramid newTextureViewWithPixelFormat:MTLPixelFormatR32Float textureType:MTLTextureType2D levels:NSMakeRange(i, 1) slices:NSMakeRange(0, 1)];
	st->mips = mips;
	st->pw = pw;
	st->ph = ph;
	st->depthW = w;
	st->depthH = h;
}

static void dispatch1(id<MTLComputeCommandEncoder> c, id<MTLComputePipelineState> ps, uint32_t n) {
	NSUInteger tg = ps.threadExecutionWidth;
	[c dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(tg, 1, 1)];
}

// The frame's parameters: projection*view of the pass, the camera (block + fraction), the frame number (>= 2).
void mc_cull_params(void *state, const float *vp, int camX, int camY, int camZ, float fx, float fy, float fz, uint32_t frame, uint32_t mode) {
	CullState *st = state;
	memcpy(st->params.vp, vp, sizeof(st->params.vp));
	st->params.cam[0] = camX;
	st->params.cam[1] = camY;
	st->params.cam[2] = camZ;
	st->params.frac[0] = fx;
	st->params.frac[1] = fy;
	st->params.frac[2] = fz;
	st->params.frac[3] = (float) mode;  // depth convention, see boxVisible
	st->params.frame = frame;
}

// Phase 1 setup for one run of indirect commands: instance count = visible last frame.
void mc_cull_a(Enc *enc, void *state, id<MTLBuffer> draws, uint64_t drawsOffset, uint32_t count, id<MTLBuffer> infos, uint64_t infosOffset) {
	if (count == 0) return;
	CullState *st = state;
	id<MTLComputeCommandEncoder> c = [mc_frame_cmd(enc) computeCommandEncoder];
	[c setComputePipelineState:st->a];
	[c setBuffer:draws offset:drawsOffset atIndex:0];
	[c setBuffer:infos offset:infosOffset atIndex:1];
	[c setBuffer:st->grid offset:0 atIndex:2];
	[c setBytes:&st->params length:sizeof(CullParams) atIndex:3];
	[c setBytes:&count length:sizeof(count) atIndex:4];
	dispatch1(c, st->a, count);
	[c endEncoding];
}

// The pyramid of the pass's depth (after phase 1) into an open compute encoder: farthest depth per texel.
static void encodePyramid(id<MTLComputeCommandEncoder> c, Ctx *ctx, CullState *st, id<MTLTexture> depth) {
	ensurePyramid(ctx, st, (uint32_t) depth.width, (uint32_t) depth.height);
	st->params.w = (uint32_t) depth.width;
	st->params.h = (uint32_t) depth.height;
	st->params.mips = st->mips;
	int rev = ((uint32_t) st->params.frac[3]) & 1u;
	[c setComputePipelineState:rev ? st->p0r : st->p0];
	[c setTexture:depth atIndex:0];
	[c setTexture:st->levels[0] atIndex:1];
	[c dispatchThreads:MTLSizeMake(st->pw, st->ph, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	[c setComputePipelineState:rev ? st->pnr : st->pn];
	for (uint32_t i = 1; i < st->mips; i++) {
		[c memoryBarrierWithScope:MTLBarrierScopeTextures];
		[c setTexture:st->levels[i - 1] atIndex:0];
		[c setTexture:st->levels[i] atIndex:1];
		[c dispatchThreads:MTLSizeMake(st->levels[i].width, st->levels[i].height, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	}
}

// The max-depth pyramid of the pass's depth (after phase 1).
void mc_cull_pyramid(Enc *enc, Ctx *ctx, void *state, id<MTLTexture> depth) {
	id<MTLComputeCommandEncoder> c = [mc_frame_cmd(enc) computeCommandEncoder];
	encodePyramid(c, ctx, state, depth);
	[c endEncoding];
}

static void cullWith(Enc *enc, CullState *st, id<MTLComputePipelineState> ps, id<MTLBuffer> draws, uint64_t drawsOffset, uint32_t count,
	id<MTLBuffer> infos, uint64_t infosOffset);

// Phase 2 for one run of indirect commands: test against the pyramid; instance count = newly visible.
void mc_cull_b(Enc *enc, void *state, id<MTLBuffer> draws, uint64_t drawsOffset, uint32_t count, id<MTLBuffer> infos, uint64_t infosOffset) {
	cullWith(enc, state, ((CullState *) state)->b, draws, drawsOffset, count, infos, infosOffset);
}

// Translucent commands: instance count = not behind this frame's pyramid.
void mc_cull_t(Enc *enc, void *state, id<MTLBuffer> draws, uint64_t drawsOffset, uint32_t count, id<MTLBuffer> infos, uint64_t infosOffset) {
	cullWith(enc, state, ((CullState *) state)->t, draws, drawsOffset, count, infos, infosOffset);
}

static void cullWith(Enc *enc, CullState *st, id<MTLComputePipelineState> ps, id<MTLBuffer> draws, uint64_t drawsOffset, uint32_t count,
	id<MTLBuffer> infos, uint64_t infosOffset) {
	if (count == 0) return;
	id<MTLComputeCommandEncoder> c = [mc_frame_cmd(enc) computeCommandEncoder];
	[c setComputePipelineState:ps];
	[c setBuffer:draws offset:drawsOffset atIndex:0];
	[c setBuffer:infos offset:infosOffset atIndex:1];
	[c setBuffer:st->grid offset:0 atIndex:2];
	[c setBytes:&st->params length:sizeof(CullParams) atIndex:3];
	[c setBytes:&count length:sizeof(count) atIndex:4];
	id<MTLTexture> lv[14];
	for (uint32_t i = 0; i < 14; i++) lv[i] = st->levels[i < st->mips ? i : st->mips - 1];
	[c setTextures:lv withRange:NSMakeRange(0, 14)];
	dispatch1(c, ps, count);
	[c endEncoding];
}

// The commands phase 1 didn't draw get drawn, the ones it drew don't (no culling this frame).
void mc_cull_rest(Enc *enc, void *state, id<MTLBuffer> draws, uint64_t drawsOffset, uint32_t count) {
	if (count == 0) return;
	CullState *st = state;
	id<MTLComputeCommandEncoder> c = [mc_frame_cmd(enc) computeCommandEncoder];
	[c setComputePipelineState:st->one];
	[c setBuffer:draws offset:drawsOffset atIndex:0];
	[c setBytes:&count length:sizeof(count) atIndex:4];
	dispatch1(c, st->one, count);
	[c endEncoding];
}

// ---- Cadence: two-phase culling inside the vanilla terrain pass (no shader pack) ----

// Phase 1 setup in the pre command buffer (it runs before the frame, so the pass isn't split for it): instance count =
// visible last frame. The commands are written by the CPU before the frame is submitted; the grid by last frame's phase 2.
void mc_cull_a_pre(Enc *enc, void *state, id<MTLBuffer> draws, uint64_t drawsOffset, uint32_t count, id<MTLBuffer> infos, uint64_t infosOffset) {
	if (count == 0) return;
	CullState *st = state;
	id<MTLComputeCommandEncoder> c = mc_pre_compute(enc);
	[c setComputePipelineState:st->a];
	[c setBuffer:draws offset:drawsOffset atIndex:0];
	[c setBuffer:infos offset:infosOffset atIndex:1];
	[c setBuffer:st->grid offset:0 atIndex:2];
	[c setBytes:&st->params length:sizeof(CullParams) atIndex:3];
	[c setBytes:&count length:sizeof(count) atIndex:4];
	dispatch1(c, st->a, count);
	// (a serial encoder: the next run's dispatch waits for this one; no barrier needed)
}

int mc_tl_restart_ok(Enc *enc);

// Whether the open pass can be split and reopened exactly (mc_tl_restart_ok): checked before phase 1 is set up.
int mc_cull_split_ok(Enc *enc) {
	return mc_tl_restart_ok(enc) == 0;
}

// Mid-pass: the open pass ends (attachments stored), the pyramid of its depth is built, and the compute encoder is returned
// (retained) for the phase-2 tests; mc_cull_vresume reopens the pass. NULL when the pass can't be split (nothing done).
id<MTLComputeCommandEncoder> mc_cull_vsplit(Enc *enc, Ctx *ctx, void *state) {
	if (mc_tl_restart_ok(enc) != 0 || !enc->depth) return nil;
	id<MTLTexture> depth = enc->depth;
	id<MTLComputeCommandEncoder> c = mc_render_suspend(enc);
	encodePyramid(c, ctx, state, depth);
	[c memoryBarrierWithScope:MTLBarrierScopeTextures];
	return c;
}

// Phase 2 for one run of commands, on the split's encoder (the runs are independent of each other).
void mc_cull_b_on(id<MTLComputeCommandEncoder> c, void *state, id<MTLBuffer> draws, uint64_t drawsOffset, uint32_t count, id<MTLBuffer> infos,
	uint64_t infosOffset) {
	if (count == 0) return;
	CullState *st = state;
	[c setComputePipelineState:st->b];
	[c setBuffer:draws offset:drawsOffset atIndex:0];
	[c setBuffer:infos offset:infosOffset atIndex:1];
	[c setBuffer:st->grid offset:0 atIndex:2];
	[c setBytes:&st->params length:sizeof(CullParams) atIndex:3];
	[c setBytes:&count length:sizeof(count) atIndex:4];
	id<MTLTexture> lv[14];
	for (uint32_t i = 0; i < 14; i++) lv[i] = st->levels[i < st->mips ? i : st->mips - 1];
	[c setTextures:lv withRange:NSMakeRange(0, 14)];
	dispatch1(c, st->b, count);
}

// The pass reopened on the same attachments, their contents loaded back (the caller rebinds its state).
void mc_cull_vresume(Enc *enc, id<MTLComputeCommandEncoder> c) {
	mc_render_resume(enc, c);
}
