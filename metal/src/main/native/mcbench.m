// Cadence: Quick Setup's view of this Mac — what it is (sysctl, the device tree's product name, the GPU's core count) and a
// short synthetic GPU test that is the same on every Mac: a fixed scene drawn offscreen at a fixed 1920x1080, so the display
// doesn't change the result. Its own device and queue (independent of the game's frame).
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <IOKit/IOKitLib.h>
#include <sys/sysctl.h>
#include <string.h>
#include <stdlib.h>

int mc_sys_string(const char *name, char *out, int cap) {
	size_t len = (size_t) cap;
	if (sysctlbyname(name, out, &len, NULL, 0) != 0 || cap <= 0) return 0;
	out[cap - 1] = 0;
	return (int) strlen(out);
}

long long mc_sys_long(const char *name) {
	long long v = 0;
	size_t len = sizeof v;
	if (sysctlbyname(name, &v, &len, NULL, 0) != 0) return -1;
	if (len == 4) return (long long) *(int *) &v;
	return v;
}

// "MacBook Air (M2, 2022)" from IODeviceTree:/product; 0 when unknown.
int mc_product_name(char *out, int cap) {
	io_registry_entry_t e = IORegistryEntryFromPath(kIOMainPortDefault, "IODeviceTree:/product");
	if (e == MACH_PORT_NULL || cap <= 0) return 0;
	int n = 0;
	CFTypeRef v = IORegistryEntryCreateCFProperty(e, CFSTR("product-name"), kCFAllocatorDefault, 0);
	if (v && CFGetTypeID(v) == CFDataGetTypeID()) {
		CFIndex len = CFDataGetLength(v);
		if (len >= cap) len = cap - 1;
		memcpy(out, CFDataGetBytePtr(v), (size_t) len);
		out[len] = 0;
		n = (int) strlen(out);
	}
	if (v) CFRelease(v);
	IOObjectRelease(e);
	return n;
}

// The GPU's core count (AGXAccelerator "gpu-core-count"); -1 when unknown.
int mc_gpu_cores(void) {
	io_iterator_t it;
	if (IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching("AGXAccelerator"), &it) != KERN_SUCCESS) return -1;
	int cores = -1;
	io_object_t s;
	while ((s = IOIteratorNext(it)) != 0) {
		CFTypeRef v = IORegistryEntryCreateCFProperty(s, CFSTR("gpu-core-count"), kCFAllocatorDefault, 0);
		if (v && CFGetTypeID(v) == CFNumberGetTypeID()) CFNumberGetValue(v, kCFNumberIntType, &cores);
		if (v) CFRelease(v);
		IOObjectRelease(s);
		if (cores > 0) break;
	}
	IOObjectRelease(it);
	return cores;
}

static NSString *const BENCH_SRC = @
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct V { float4 pos [[position]]; float3 n; float2 uv; };\n"
"constant float3 CORNER[8] = { float3(0,0,0), float3(1,0,0), float3(0,1,0), float3(1,1,0), float3(0,0,1), float3(1,0,1), float3(0,1,1), float3(1,1,1) };\n"
"constant uint FACE[36] = { 0,2,1, 1,2,3, 4,5,6, 5,7,6, 0,1,4, 1,5,4, 2,6,3, 3,6,7, 0,4,2, 2,4,6, 1,3,5, 3,7,5 };\n"
"constant float3 NORMAL[6] = { float3(0,0,-1), float3(0,0,1), float3(0,-1,0), float3(0,1,0), float3(-1,0,0), float3(1,0,0) };\n"
// a 96 x 16 x 96 field of unit cubes (147,456 cubes, ~1.8M triangles), seen from above one corner looking across it
"vertex V cube_vs(uint vid [[vertex_id]], uint iid [[instance_id]], constant float4x4 &mvp [[buffer(0)]]) {\n"
"  uint x = iid % 96u, z = (iid / 96u) % 96u, y = iid / 9216u;\n"
"  float3 p = float3(float(x) * 1.6, float(y) * 1.6, float(z) * 1.6) + CORNER[FACE[vid]];\n"
"  V o; o.pos = mvp * float4(p, 1.0); o.n = NORMAL[vid / 6u];\n"
"  float2 uv = float2(fract(p.x + p.z), fract(p.y + p.z)); o.uv = uv * 16.0; return o;\n"
"}\n"
// half the cubes alpha-tested like leaves (a hashed 16x16 pattern), the rest opaque; simple directional light
"fragment float4 cube_fs(V in [[stage_in]]) {\n"
"  uint2 t = uint2(in.uv);\n"
"  uint h = (t.x * 73856093u) ^ (t.y * 19349663u);\n"
"  if ((h & 7u) < 3u && in.pos.z > 0.0 && (uint(in.pos.x) & 1u) == 0u) discard_fragment();\n"
"  float l = 0.4 + 0.6 * saturate(dot(normalize(in.n), normalize(float3(0.3, 1.0, 0.2))));\n"
"  return float4(float3(0.2, 0.6, 0.25) * l, 1.0);\n"
"}\n"
"struct F { float4 pos [[position]]; float2 uv; };\n"
"vertex F full_vs(uint vid [[vertex_id]]) {\n"
"  float2 p = float2((vid << 1) & 2, vid & 2); F o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); o.uv = p; return o;\n"
"}\n"
// a shader-pack-like full-screen pass: 48 dependent taps of procedural lighting math per pixel
"fragment float4 full_fs(F in [[stage_in]], texture2d<float> src [[texture(0)]]) {\n"
"  constexpr sampler s(filter::linear, address::clamp_to_edge);\n"
"  float3 acc = 0.0; float2 uv = in.uv;\n"
"  for (int i = 0; i < 48; i++) {\n"
"    float a = float(i) * 0.37;\n"
"    float2 o = float2(cos(a), sin(a)) * (0.002 * float(i % 8));\n"
"    acc += src.sample(s, uv + o).rgb * (0.5 + 0.5 * sin(a + uv.x * 9.0));\n"
"  }\n"
"  return float4(acc / 48.0, 1.0);\n"
"}\n";

static double median3(double a, double b, double c) {
	if (a > b) { double t = a; a = b; b = t; }
	if (b > c) { double t = b; b = c; c = t; }
	if (a > b) { double t = a; a = b; b = t; }
	return b;
}

// out[0]: ms of GPU per frame of the cube scene, out[1]: of the full-screen pass (4 passes per frame); each the median of
// three runs of 12 frames after 4 warm-up frames. Returns 0 on failure.
int mc_bench_gpu(double *out) {
	@autoreleasepool {
		id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
		if (!dev) return 0;
		NSError *err = nil;
		id<MTLLibrary> lib = [dev newLibraryWithSource:BENCH_SRC options:nil error:&err];
		if (!lib) {
			NSLog(@"cadence: Quick Setup GPU test failed to build: %@", err);
			[dev release];
			return 0;
		}
		const int W = 1920, H = 1080;
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:W height:H mipmapped:NO];
		td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
		td.storageMode = MTLStorageModePrivate;
		id<MTLTexture> color = [dev newTextureWithDescriptor:td], color2 = [dev newTextureWithDescriptor:td];
		td.pixelFormat = MTLPixelFormatDepth32Float;
		td.usage = MTLTextureUsageRenderTarget;
		id<MTLTexture> depth = [dev newTextureWithDescriptor:td];
		MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
		pd.vertexFunction = [lib newFunctionWithName:@"cube_vs"];
		pd.fragmentFunction = [lib newFunctionWithName:@"cube_fs"];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
		pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
		id<MTLRenderPipelineState> cube = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
		[pd.vertexFunction release];
		[pd.fragmentFunction release];
		pd.vertexFunction = [lib newFunctionWithName:@"full_vs"];
		pd.fragmentFunction = [lib newFunctionWithName:@"full_fs"];
		pd.depthAttachmentPixelFormat = MTLPixelFormatInvalid;
		id<MTLRenderPipelineState> full = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
		[pd.vertexFunction release];
		[pd.fragmentFunction release];
		[pd release];
		MTLDepthStencilDescriptor *dd = [MTLDepthStencilDescriptor new];
		dd.depthCompareFunction = MTLCompareFunctionLess;
		dd.depthWriteEnabled = YES;
		id<MTLDepthStencilState> ds = [dev newDepthStencilStateWithDescriptor:dd];
		[dd release];
		id<MTLCommandQueue> q = [dev newCommandQueue];
		int ok = cube && full && q;
		// a fixed camera: above one corner of the field, looking across it (column-major perspective * look-at, precomputed)
		float mvp[16];
		{
			float fov = 1.2f, aspect = (float) W / H, n = 0.5f, f = 400.0f, t = 1.0f / tanf(fov / 2);
			float ex = -10, ey = 40, ez = -10, cx = 80, cy = 0, cz = 80;
			float fx = cx - ex, fy = cy - ey, fz = cz - ez, fl = sqrtf(fx * fx + fy * fy + fz * fz);
			fx /= fl; fy /= fl; fz /= fl;
			float sx = fy * 0 - fz * 1, sy = fz * 0 - fx * 0, sz = fx * 1 - fy * 0, sl = sqrtf(sx * sx + sy * sy + sz * sz);
			sx /= sl; sy /= sl; sz /= sl;
			float ux = sy * fz - sz * fy, uy = sz * fx - sx * fz, uz = sx * fy - sy * fx;
			float v[16] = {sx, ux, -fx, 0, sy, uy, -fy, 0, sz, uz, -fz, 0, -(sx * ex + sy * ey + sz * ez), -(ux * ex + uy * ey + uz * ez),
				(fx * ex + fy * ey + fz * ez), 1};
			float p[16] = {t / aspect, 0, 0, 0, 0, t, 0, 0, 0, 0, f / (n - f), -1, 0, 0, n * f / (n - f), 0};
			for (int c = 0; c < 4; c++)
				for (int r = 0; r < 4; r++) {
					float s = 0;
					for (int k = 0; k < 4; k++) s += p[k * 4 + r] * v[c * 4 + k];
					mvp[c * 4 + r] = s;
				}
		}
		for (int part = 0; part < 2 && ok; part++) {
			double runs[3];
			for (int run = 0; run < 3; run++) {
				__block double sum = 0;
				__block int n = 0;
				id<MTLCommandBuffer> last = nil;
				for (int frame = 0; frame < 16; frame++) {
					id<MTLCommandBuffer> cb = [q commandBuffer];
					if (part == 0) {
						MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
						rp.colorAttachments[0].texture = color;
						rp.colorAttachments[0].loadAction = MTLLoadActionClear;
						rp.colorAttachments[0].storeAction = MTLStoreActionStore;
						rp.depthAttachment.texture = depth;
						rp.depthAttachment.loadAction = MTLLoadActionClear;
						rp.depthAttachment.storeAction = MTLStoreActionDontCare;
						rp.depthAttachment.clearDepth = 1.0;
						id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
						[r setRenderPipelineState:cube];
						[r setDepthStencilState:ds];
						[r setVertexBytes:mvp length:sizeof mvp atIndex:0];
						[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:36 instanceCount:96 * 96 * 16];
						[r endEncoding];
					} else {
						for (int pass = 0; pass < 4; pass++) {
							MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
							rp.colorAttachments[0].texture = pass % 2 ? color : color2;
							rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
							rp.colorAttachments[0].storeAction = MTLStoreActionStore;
							id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
							[r setRenderPipelineState:full];
							[r setFragmentTexture:pass % 2 ? color2 : color atIndex:0];
							[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
							[r endEncoding];
						}
					}
					if (frame >= 4) [cb addCompletedHandler:^(id<MTLCommandBuffer> c) {
						sum += (c.GPUEndTime - c.GPUStartTime) * 1000.0;
						n++;
					}];
					[cb commit];
					[cb waitUntilCompleted];
					last = cb;
				}
				[last waitUntilCompleted];
				runs[run] = n > 0 ? sum / n : -1;
			}
			out[part] = median3(runs[0], runs[1], runs[2]);
		}
		[cube release];
		[full release];
		[ds release];
		[q release];
		[color release];
		[color2 release];
		[depth release];
		[lib release];
		[dev release];
		return ok;
	}
}
