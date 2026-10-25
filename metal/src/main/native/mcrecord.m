// Cadence: Record Mode (dev.cadence.metal.record, record.metal): the frame as compute only. A render pass costs the M2's GPU
// about 53 us whatever its size (tools/m4_floor.m), a compute dispatch far less, so the frame is one dispatch: the world traced
// into this frame's colour and depth, the HUD overlay (RecordHud) blended in, and, with interpolation on, the in-between frame
// of the last two. The game presents the colour texture at most once a refresh (Render Max FPS's uncapped presentation).
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import "mcmetal.h"
#include <stdatomic.h>

id<MTLCommandBuffer> mc_frame_cmd(Enc *enc);

// GPU time per record frame, sampled from about every 64th frame's command buffer: its time over the record frames in it
// (Record Mode commits several to a command buffer: MetalEncoder.recordBatch). Microseconds, averaged since the last read.
static _Atomic double gpuSum;
static _Atomic long gpuCount;
double mcrm_gpu_us(void) {
	long n = atomic_exchange(&gpuCount, 0);
	double s = atomic_exchange(&gpuSum, 0.0);
	return n > 0 ? s / n : -1;
}

// Frames overlap on the GPU: frame N writes slot N % RING and makes its in-between frame from frames N-2 and N-3, so it never
// reads what the frame just before it is still writing. The textures aren't tracked by Metal (tracking serialised every
// frame behind the last one); fences order what must be: frame N waits for frames N-2 and N-3 (what it reads, and the last
// readers of the slot it overwrites, which with 5 slots are those two), and the present and test reads wait for the frame
// they show. Overlapping frames: +11-16% (tools/record_bench.m RB_UNTRACKED). On Metal 4 the textures stay tracked.
#define RING 5
#define LAG_A 2
#define LAG_B 3

typedef struct {
	id<MTLComputePipelineState> trace;
	id<MTLTexture> color[RING], depth[RING], mid[RING];
	id<MTLFence> fence[RING];  // updated by the frame that writes the slot (Metal 3 only)
	id<MTLFunction> drawVs, drawFs;               // the frame drawn into a game target (mcrm_draw), and its pipeline
	id<MTLRenderPipelineState> draw;
	MTLPixelFormat drawFormat;
	int w, h, cur, last;       // the slot the next frame writes; the last frame's
	long frames;
	id<MTLCommandBuffer> lastCb;  // (retained, so no new one can reuse its address) the command buffer of the last frame
	_Atomic int *sampledFrames;  // the record frames in it, when it's being sampled
	long lastSample;
} Rec;

static void copyErr(NSError *e, char *err, int cap) {
	if (err && cap > 0) strlcpy(err, e ? e.localizedDescription.UTF8String : "unknown error", cap);
}

void *mcrm_new(Ctx *ctx, const char *source, char *err, int errCap) {
	@autoreleasepool {
		NSError *e = nil;
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.mathMode = MTLMathModeFast;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) { copyErr(e, err, errCap); return NULL; }
		Rec *r = calloc(1, sizeof(Rec));
		id<MTLFunction> t = [[lib newFunctionWithName:@"record_trace"] autorelease];
		r->trace = [ctx->device newComputePipelineStateWithFunction:t error:&e];
		r->drawVs = [lib newFunctionWithName:@"record_draw_vs"];
		r->drawFs = [lib newFunctionWithName:@"record_draw_fs"];
		[lib release];
		if (!r->trace) {
			copyErr(e, err, errCap);
			free(r);
			return NULL;
		}
		if (!mc4On)
			for (int k = 0; k < RING; k++) r->fence[k] = [ctx->device newFence];
		return r;
	}
}

static id<MTLTexture> tex(Ctx *ctx, MTLPixelFormat f, int w, int h) {
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:w height:h mipmapped:NO];
	d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
	d.storageMode = MTLStorageModePrivate;
	if (!mc4On) d.hazardTrackingMode = MTLHazardTrackingModeUntracked;  // (ordered by the fences instead)
	return [ctx->device newTextureWithDescriptor:d];
}

static void dropTextures(Rec *r) {
	for (int k = 0; k < RING; k++) {
		if (mc4On) { mc4_unpin(r->color[k]); mc4_unpin(r->depth[k]); mc4_unpin(r->mid[k]); }
		[r->color[k] release];
		[r->depth[k] release];
		[r->mid[k] release];
		r->color[k] = r->depth[k] = r->mid[k] = nil;
	}
}

// The output size (the record resolution): textures made again when it changes.
void mcrm_size(void *rec, Ctx *ctx, int w, int h) {
	Rec *r = rec;
	if (r->w == w && r->h == h && r->color[0]) return;
	dropTextures(r);
	for (int k = 0; k < RING; k++) {
		r->color[k] = tex(ctx, MTLPixelFormatRGBA8Unorm, w, h);
		r->depth[k] = tex(ctx, MTLPixelFormatR16Float, w, h);  // (half: ample for reprojection within 28 blocks; sky is +inf)
		r->mid[k] = tex(ctx, MTLPixelFormatRGBA8Unorm, w, h);
	}
	r->w = w;
	r->h = h;
	r->frames = 0;
	r->lastSample = -64;
}


// One frame on the frame's command buffer: one dispatch traces it and, with interpolation on (the game passes it in the
// frame's flags), makes the in-between frame of the last two. Returns the texture to present: this frame's colour.
id<MTLTexture> mcrm_frame(void *rec, Enc *enc, const void *frame, int frameLength, id<MTLBuffer> grid, id<MTLBuffer> pal,
	id<MTLBuffer> entities, id<MTLBuffer> hud, id<MTLBuffer> occ, id<MTLBuffer> light, int interpolate) {
	(void) interpolate;  // (in the frame's flags)
	Rec *r = rec;
	if (!r->color[0]) return nil;
	int cur = r->cur, a = (cur + RING - LAG_A) % RING, b = (cur + RING - LAG_B) % RING;  // (a: frame N-2, b: N-3)
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		id<MTLComputeCommandEncoder> c = [cb computeCommandEncoderWithDispatchType:MTLDispatchTypeSerial];
		[c setComputePipelineState:r->trace];
		[c setTexture:r->color[cur] atIndex:0];
		[c setTexture:r->depth[cur] atIndex:1];
		[c setTexture:r->color[a] atIndex:4];
		[c setTexture:r->depth[a] atIndex:5];
		[c setTexture:r->color[b] atIndex:6];
		[c setTexture:r->depth[b] atIndex:7];
		[c setTexture:r->mid[cur] atIndex:8];
		if (r->fence[0]) {
			[c waitForFence:r->fence[a]];
			[c waitForFence:r->fence[b]];
		}
		[c setBytes:frame length:frameLength atIndex:0];
		[c setBuffer:grid offset:0 atIndex:1];
		[c setBuffer:pal offset:0 atIndex:3];
		[c setBuffer:entities offset:0 atIndex:4];
		[c setBuffer:hud offset:0 atIndex:5];
		[c setBuffer:occ offset:0 atIndex:6];
		[c setBuffer:light offset:0 atIndex:7];
		[c setBuffer:entities offset:32 * 48 atIndex:8];  // (the entity tiles' masks, after the boxes: Record.ENTITY_REGION)
		[c dispatchThreads:MTLSizeMake(r->w, r->h, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		if (r->fence[0]) [c updateFence:r->fence[cur]];
		[c endEncoding];
		if (cb != r->lastCb) {
			// the first record frame in this command buffer: sampled when one is due (its time over its record frames)
			[r->lastCb release];
			r->lastCb = [cb retain];
			r->sampledFrames = NULL;
			if (r->frames - r->lastSample >= 64) {
				r->lastSample = r->frames;
				_Atomic int *n = calloc(1, sizeof(_Atomic int));
				atomic_store(n, 1);
				r->sampledFrames = n;
				[cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
					double us = (done.GPUEndTime - done.GPUStartTime) * 1e6 / atomic_load(n);
					if (us > 0 && us < 1e5) {
						double cur = atomic_load(&gpuSum);
						while (!atomic_compare_exchange_weak(&gpuSum, &cur, cur + us)) {}
						atomic_fetch_add(&gpuCount, 1);
					}
					free(n);
				}];
			}
		} else if (r->sampledFrames) atomic_fetch_add(r->sampledFrames, 1);
	}
	r->last = cur;
	r->cur = (cur + 1) % RING;
	r->frames++;
	return r->color[cur];
}

// The last frame's in-between frame (frame interpolation's), presented every other present; nil before there is one.
id<MTLTexture> mcrm_mid(void *rec) {
	Rec *r = rec;
	return r && r->frames > LAG_B ? r->mid[r->last] : nil;
}

// The fence a reader of the last frame's textures waits for (nil on Metal 4: tracked there).
id<MTLFence> mcrm_fence(void *rec) {
	Rec *r = rec;
	return r && r->frames > 0 ? r->fence[r->last] : nil;
}

// The last frame drawn over a game texture (the whole of it, scaled, nearest), on the frame's command buffer: Record Mode under
// a screen (inventory, chat, pause), which the game then draws over. Both are stored bottom row first. 0: not drawn.
int mcrm_draw(void *rec, Enc *enc, Ctx *ctx, id<MTLTexture> target) {
	Rec *r = rec;
	if (!r || !target || r->frames == 0 || !r->drawVs) return 0;
	@autoreleasepool {
		if (!r->draw || r->drawFormat != target.pixelFormat) {
			MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor new] autorelease];
			d.vertexFunction = r->drawVs;
			d.fragmentFunction = r->drawFs;
			d.colorAttachments[0].pixelFormat = target.pixelFormat;
			NSError *e = nil;
			[r->draw release];
			r->draw = [ctx->device newRenderPipelineStateWithDescriptor:d error:&e];
			r->drawFormat = target.pixelFormat;
			if (!r->draw) return 0;
		}
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = target;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> c = [mc_frame_cmd(enc) renderCommandEncoderWithDescriptor:rp];
		if (r->fence[0]) [c waitForFence:r->fence[r->last] beforeStages:MTLRenderStageFragment];
		[c setRenderPipelineState:r->draw];
		[c setFragmentTexture:r->color[r->last] atIndex:0];
		[c drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[c endEncoding];
	}
	return 1;
}

void mcrm_release(void *rec) {
	Rec *r = rec;
	if (!r) return;
	dropTextures(r);
	for (int k = 0; k < RING; k++) [r->fence[k] release];
	[r->drawVs release];
	[r->drawFs release];
	[r->draw release];
	[r->lastCb release];
	[r->trace release];
	free(r);
}

// (testing) The latest frame's pixels (RGBA8, w x h) into dst: a copy to shared memory, waited for. 0: no frame yet.
int mcrm_read(void *rec, Ctx *ctx, void *dst) {
	Rec *r = rec;
	if (!r || !r->color[0] || r->frames == 0) return 0;
	id<MTLTexture> t = r->color[r->last];
	@autoreleasepool {
		id<MTLBuffer> b = [ctx->device newBufferWithLength:(NSUInteger) r->w * r->h * 4 options:MTLResourceStorageModeShared];
		id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
		id<MTLBlitCommandEncoder> e = [cb blitCommandEncoder];
		if (r->fence[0]) [e waitForFence:r->fence[r->last]];
		[e copyFromTexture:t sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(r->w, r->h, 1)
			toBuffer:b destinationOffset:0 destinationBytesPerRow:r->w * 4 destinationBytesPerImage:r->w * r->h * 4];
		[e endEncoding];
		[cb commit];
		[cb waitUntilCompleted];
		memcpy(dst, b.contents, (size_t) r->w * r->h * 4);
		if (mc4On) mc4_unpin(b);
		[b release];
	}
	return 1;
}
