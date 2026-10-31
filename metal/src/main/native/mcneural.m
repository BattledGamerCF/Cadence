// Cadence: the Neural Engine features (dev.cadence.metal.neural). Core ML and VideoToolbox's ML processors run on the Mac's
// Neural Engine, beside the GPU: the GPU only converts pictures to and from their pixel buffers.
//
// NnStyle Filters (StyleFilter.java): a style transfer model (trained on this Mac by Cadence Helper with Create ML) run on the
// Neural Engine. Each frame the world (before the hand and the HUD) is scaled into the model's 512 x 512 input on the GPU; once
// the GPU is done, a serial queue runs the model; the newest result is drawn over the next frames' world, blended by strength.
// One frame is in the model at a time (about 28 ms on an M2): when it's busy the frame isn't sent, the last result stays.
#include <unistd.h>
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <CoreML/CoreML.h>
#import <CoreVideo/CoreVideo.h>
#include <os/lock.h>
#include <stdatomic.h>
#import <QuartzCore/QuartzCore.h>
#include <dlfcn.h>
#import "mcmetal.h"

// Core ML is opened at the first Neural Engine feature's start (dlopen; its classes found by name): without one the process
// never loads it.
static Class cMLModel, cMLModelConfiguration, cMLDictionaryFeatureProvider, cMLFeatureValue, cMLPredictionOptions;
static int nnLoad(char *err, int errCap) {
	if (cMLModel) return 0;
	if (!dlopen("/System/Library/Frameworks/CoreML.framework/CoreML", RTLD_NOW | RTLD_LOCAL)) {
		snprintf(err, errCap, "can't open Core ML: %s", dlerror());
		return -1;
	}
	cMLModel = NSClassFromString(@"MLModel");
	cMLModelConfiguration = NSClassFromString(@"MLModelConfiguration");
	cMLDictionaryFeatureProvider = NSClassFromString(@"MLDictionaryFeatureProvider");
	cMLFeatureValue = NSClassFromString(@"MLFeatureValue");
	cMLPredictionOptions = NSClassFromString(@"MLPredictionOptions");
	if (!cMLModel || !cMLModelConfiguration || !cMLDictionaryFeatureProvider || !cMLFeatureValue) {
		snprintf(err, errCap, "Core ML classes missing");
		cMLModel = nil;
		return -1;
	}
	return 0;
}

id<MTLCommandBuffer> mc_frame_cmd(Enc *enc);

static NSString *neuralSource = @
	"#include <metal_stdlib>\n"
	"using namespace metal;\n"
	// the frame (rows bottom first, sRGB values, maybe above 1.0 with HDR) into the model's input (rows top first, BGRA)
	// (margin: pixels on each side of the model's input mirrored from inside the picture, cut off again in nn_over: the network
	// pads with zeros, which darkens the edges it sees)
	"kernel void nn_down(texture2d<float, access::sample> src [[texture(0)]], texture2d<float, access::write> dst [[texture(1)]],\n"
	"    constant int &margin [[buffer(0)]], uint2 g [[thread_position_in_grid]]) {\n"
	"  uint w = dst.get_width(), h = dst.get_height(); if (g.x >= w || g.y >= h) return;\n"
	"  constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::linear);\n"
	"  float2 inner = float2(w, h) - 2.0 * float(margin);\n"
	"  float2 uv = (float2(g) + 0.5 - float(margin)) / inner; uv = select(uv, -uv, uv < 0.0); uv = select(uv, 2.0 - uv, uv > 1.0); uv.y = 1.0 - uv.y;\n"
	"  float2 px = 1.0 / float2(src.get_width(), src.get_height()), step = float2(src.get_width() / inner.x, src.get_height() / inner.y) * px * 0.25;\n"
	"  float3 c = 0; for (int k = 0; k < 4; k++) c += src.sample(s, uv + float2((k & 1) ? step.x : -step.x, (k & 2) ? step.y : -step.y)).rgb;\n"
	"  dst.write(float4(saturate(c * 0.25), 1.0), g);\n"
	"}\n"
	// Smooth Textures: the animated sprites' current frames (level 0) copied into the 2x atlas, each pixel twice each way
	"kernel void nn_anim(texture2d<float, access::read> src [[texture(0)]], texture2d<float, access::write> dst [[texture(1)]],\n"
	"    device const int4 *rects [[buffer(0)]], uint3 g [[thread_position_in_grid]]) {\n"
	"  int4 r = rects[g.z]; if (int(g.x) >= r.z * 2 || int(g.y) >= r.w * 2) return;\n"
	"  dst.write(src.read(uint2(r.x + int(g.x) / 2, r.y + int(g.y) / 2)), uint2(r.x * 2 + int(g.x), r.y * 2 + int(g.y)));\n"
	"}\n"
	// Neural Engine upscaler: the scaled world (RGBA, sRGB values) into a 4:2:0 picture (BT.709 video range: luma, then Cb Cr at
	// half size), rows kept as they are; and the upscaled picture back into the frame
	// (at: the tile's corner in the scaled world, the picture being upscaled in tiles when it's larger than the scaler takes)
	"kernel void nn_to420(texture2d<float, access::read> src [[texture(0)]], texture2d<float, access::write> yt [[texture(1)]],\n"
	"    texture2d<float, access::write> ct [[texture(2)]], constant uint2 &at [[buffer(0)]], uint2 g [[thread_position_in_grid]]) {\n"
	"  uint w = ct.get_width(), h = ct.get_height(); if (g.x >= w || g.y >= h) return;\n"
	"  const float3 kY = float3(0.2126, 0.7152, 0.0722); float3 m = 0;\n"
	"  for (uint k = 0; k < 4; k++) { uint2 p = min(at + g * 2 + uint2(k & 1, k >> 1), uint2(src.get_width() - 1, src.get_height() - 1));\n"
	"    float3 c = saturate(src.read(p).rgb); m += c; yt.write(float4((16.0 + 219.0 * dot(c, kY)) / 255.0), g * 2 + uint2(k & 1, k >> 1)); }\n"
	"  m *= 0.25; float y = dot(m, kY);\n"
	"  ct.write(float4((128.0 + 224.0 * (m.b - y) / 1.8556) / 255.0, (128.0 + 224.0 * (m.r - y) / 1.5748) / 255.0, 0, 0), g);\n"
	"}\n"
	"struct VU { float4 pos [[position]]; float2 uv; };\n"
	"vertex VU nn_up_vs(uint id [[vertex_id]]) { float2 p = float2((id << 1) & 2, id & 2); VU v; v.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); v.uv = float2(p.x, 1.0 - p.y); return v; }\n"
	// (t: the scaled world's size, a tile's size, the second column's and row's corner; each pixel from the tile whose half of
	// the picture it's in, so the seams fall in the tiles' overlap, away from their edges)
	"struct NnTiles { float2 world, tile, second; };\n"
	"fragment float4 nn_from420(VU v [[stage_in]], texture2d<float> y0 [[texture(0)]], texture2d<float> c0 [[texture(1)]],\n"
	"    texture2d<float> y1 [[texture(2)]], texture2d<float> c1 [[texture(3)]], texture2d<float> y2 [[texture(4)]], texture2d<float> c2 [[texture(5)]],\n"
	"    texture2d<float> y3 [[texture(6)]], texture2d<float> c3 [[texture(7)]], constant NnTiles &t [[buffer(0)]]) {\n"
	"  constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::linear);\n"
	"  float2 w = v.uv * t.world; bool2 far = t.second > 0.0 && w >= t.world * 0.5;\n"
	"  float2 uv = (w - select(float2(0.0), t.second, far)) / t.tile; int k = (far.x ? 1 : 0) + (far.y ? 2 : 0);\n"
	"  texture2d<float> yt = k == 0 ? y0 : k == 1 ? y1 : k == 2 ? y2 : y3, ct = k == 0 ? c0 : k == 1 ? c1 : k == 2 ? c2 : c3;\n"
	"  float Y = (yt.sample(s, uv).r * 255.0 - 16.0) / 219.0; float2 c = (ct.sample(s, uv).rg * 255.0 - 128.0) / 224.0;\n"
	"  float r = Y + 1.5748 * c.y, b = Y + 1.8556 * c.x, gg = (Y - 0.2126 * r - 0.0722 * b) / 0.7152;\n"
	"  return float4(saturate(float3(r, gg, b)), 1.0);\n"
	"}\n"
	"struct V { float4 pos [[position]]; float2 uv; };\n"
	"vertex V nn_vs(uint id [[vertex_id]]) { float2 p = float2((id << 1) & 2, id & 2); V v; v.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); v.uv = float2(p.x, 1.0 - p.y); return v; }\n"
	// the model's output (rows top first) over the frame (rows bottom first: the pass's y is flipped by the game's row order)
	"fragment float4 nn_over(V v [[stage_in]], texture2d<float> t [[texture(0)]], constant float2 &p [[buffer(0)]]) {\n"
	"  constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::linear);\n"
	"  float2 size = float2(t.get_width(), t.get_height()), uv = (p.y + float2(v.uv.x, 1.0 - v.uv.y) * (size - 2.0 * p.y)) / size;\n"
	"  return float4(t.sample(s, uv).rgb, p.x);\n"
	"}\n";

typedef struct {
	Ctx *ctx;
	MLModel *model;
	NSString *inName, *outName;
	int inW, inH, margin;
	double lastStart;  // when the newest run was sent (at most 60 a second: the Neural Engine isn't kept busy for frames not seen)
	CVPixelBufferPoolRef pool, outPool;  // the model's input, and its output (Metal-compatible: Core ML writes into it)
	CVMetalTextureCacheRef cache;
	id<MTLComputePipelineState> down;
	id<MTLFunction> vs, over;
	id<MTLRenderPipelineState> overPso[2];
	dispatch_queue_t q;
	os_unfair_lock lock;
	CVPixelBufferRef latest;  // the newest result (retained), under lock
	_Atomic int busy;
	_Atomic long runs, skipped;
	_Atomic double lastMs;
} NnStyle;

static void nnErr(NSError *e, const char *what, char *err, int cap) {
	snprintf(err, cap, "%s: %s", what, e ? e.localizedDescription.UTF8String : "unknown error");
}

// modelPath: a .mlmodel (compiled to compiledPath, a .mlmodelc, the first time) or the compiled model itself.
void *mcst_new(Ctx *ctx, const char *modelPath, const char *compiledPath, int margin, char *err, int errCap) {
	@autoreleasepool {
		if (nnLoad(err, errCap) != 0) return NULL;
		NSError *e = nil;
		NSURL *compiled = [NSURL fileURLWithPath:[NSString stringWithUTF8String:compiledPath]];
		if (![[NSFileManager defaultManager] fileExistsAtPath:compiled.path]) {
			NSURL *tmp = [(id) cMLModel compileModelAtURL:[NSURL fileURLWithPath:[NSString stringWithUTF8String:modelPath]] error:&e];
			if (!tmp) { nnErr(e, "compile", err, errCap); return NULL; }
			[[NSFileManager defaultManager] removeItemAtURL:compiled error:nil];
			if (![[NSFileManager defaultManager] moveItemAtURL:tmp toURL:compiled error:&e]) { nnErr(e, "keep compiled model", err, errCap); return NULL; }
		}
		MLModelConfiguration *cfg = [[(id) cMLModelConfiguration new] autorelease];
		cfg.computeUnits = MLComputeUnitsCPUAndNeuralEngine;
		MLModel *model = [(id) cMLModel modelWithContentsOfURL:compiled configuration:cfg error:&e];
		if (!model) { nnErr(e, "load model", err, errCap); return NULL; }
		NSString *inName = nil, *outName = nil;
		MLImageConstraint *ic = nil;
		for (NSString *k in model.modelDescription.inputDescriptionsByName) {
			MLFeatureDescription *d = model.modelDescription.inputDescriptionsByName[k];
			if (d.type == MLFeatureTypeImage) { inName = k; ic = d.imageConstraint; }
		}
		for (NSString *k in model.modelDescription.outputDescriptionsByName)
			if (model.modelDescription.outputDescriptionsByName[k].type == MLFeatureTypeImage) outName = k;
		if (!inName || !outName || !ic) { snprintf(err, errCap, "not an image-to-image model"); return NULL; }
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		id<MTLLibrary> lib = [[ctx->device newLibraryWithSource:neuralSource options:o error:&e] autorelease];
		id<MTLFunction> dfn = lib ? [[lib newFunctionWithName:@"nn_down"] autorelease] : nil;
		id<MTLComputePipelineState> down = dfn ? [ctx->device newComputePipelineStateWithFunction:dfn error:&e] : nil;
		if (!down) { nnErr(e, "nn_down", err, errCap); return NULL; }
		NnStyle *s = calloc(1, sizeof(NnStyle));
		s->ctx = ctx;
		s->model = [model retain];
		s->inName = [inName retain];
		s->outName = [outName retain];
		s->inW = (int) ic.pixelsWide;
		s->inH = (int) ic.pixelsHigh;
		s->margin = margin;
		s->down = down;
		s->vs = [lib newFunctionWithName:@"nn_vs"];
		s->over = [lib newFunctionWithName:@"nn_over"];
		s->lock = OS_UNFAIR_LOCK_INIT;
		NSDictionary *pb = @{(id) kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA), (id) kCVPixelBufferWidthKey: @(s->inW),
			(id) kCVPixelBufferHeightKey: @(s->inH), (id) kCVPixelBufferIOSurfacePropertiesKey: @{}, (id) kCVPixelBufferMetalCompatibilityKey: @YES};
		CVPixelBufferPoolCreate(NULL, (CFDictionaryRef) @{(id) kCVPixelBufferPoolMinimumBufferCountKey: @2}, (CFDictionaryRef) pb, &s->pool);
		// (the output into buffers of ours: those Core ML makes itself may be laid out for the Neural Engine, not for Metal)
		CVPixelBufferPoolCreate(NULL, (CFDictionaryRef) @{(id) kCVPixelBufferPoolMinimumBufferCountKey: @3}, (CFDictionaryRef) pb, &s->outPool);
		CVMetalTextureCacheCreate(NULL, NULL, ctx->device, NULL, &s->cache);
		s->q = dispatch_queue_create("cadence.style", DISPATCH_QUEUE_SERIAL);
		dispatch_set_target_queue(s->q, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0));
		return s;
	}
}

static id<MTLRenderPipelineState> overPipeline(NnStyle *s, MTLPixelFormat format) {
	int k = format == MTLPixelFormatRGBA16Float ? 1 : 0;
	if (s->overPso[k]) return s->overPso[k];
	MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor new] autorelease];
	d.vertexFunction = s->vs;
	d.fragmentFunction = s->over;
	d.colorAttachments[0].pixelFormat = format;
	d.colorAttachments[0].blendingEnabled = YES;
	d.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
	d.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
	d.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorZero;
	d.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;
	NSError *e = nil;
	s->overPso[k] = [s->ctx->device newRenderPipelineStateWithDescriptor:d error:&e];
	if (!s->overPso[k]) NSLog(@"cadence-style: %@", e);
	return s->overPso[k];
}

// Render thread, after the world is drawn: this frame sent to the model (unless it's still busy with an earlier one), and the
// newest result drawn over the frame. Returns 1 if a result was drawn.
int mcst_frame(void *st, Enc *enc, id<MTLTexture> color, float strength) {
	NnStyle *s = st;
	if (!color) return 0;
	int drew = 0;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		// 1. the frame into the model's input, then the model on the queue once the GPU is done
		double now = CACurrentMediaTime();
		if (now - s->lastStart < 1.0 / 60.0) {
			// (the last run is newer than a 60 Hz frame: this frame shows it)
		} else if (atomic_exchange(&s->busy, 1) == 0) {
			s->lastStart = now;
			CVPixelBufferRef in = NULL;
			CVMetalTextureRef tin = NULL;
			if (CVPixelBufferPoolCreatePixelBuffer(NULL, s->pool, &in) == kCVReturnSuccess
				&& CVMetalTextureCacheCreateTextureFromImage(NULL, s->cache, in,
					(CFDictionaryRef) @{(id) kCVMetalTextureUsage: @(MTLTextureUsageShaderWrite | MTLTextureUsageShaderRead)},
					MTLPixelFormatBGRA8Unorm, (size_t) s->inW, (size_t) s->inH, 0, &tin) == kCVReturnSuccess) {
				id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
				c.label = @"Cadence style (to the model)";
				[c setComputePipelineState:s->down];
				[c setTexture:color atIndex:0];
				[c setTexture:CVMetalTextureGetTexture(tin) atIndex:1];
				[c setBytes:&s->margin length:4 atIndex:0];
				[c dispatchThreads:MTLSizeMake((NSUInteger) s->inW, (NSUInteger) s->inH, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
				[c endEncoding];
				NnStyle *ss = s;
				[cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
					CFRelease(tin);
					dispatch_async(ss->q, ^{
						@autoreleasepool {
							double t0 = CACurrentMediaTime();
							NSError *e = nil;
							MLDictionaryFeatureProvider *p = [[[(id) cMLDictionaryFeatureProvider alloc] initWithDictionary:@{ss->inName: [(id) cMLFeatureValue featureValueWithPixelBuffer:in]} error:&e] autorelease];
							CVPixelBufferRef backing = NULL;
							CVPixelBufferPoolCreatePixelBuffer(NULL, ss->outPool, &backing);
							MLPredictionOptions *opt = [[(id) cMLPredictionOptions new] autorelease];
							if (backing && [opt respondsToSelector:@selector(setOutputBackings:)]) opt.outputBackings = @{ss->outName: (id) backing};
							id<MLFeatureProvider> outp = p ? [ss->model predictionFromFeatures:p options:opt error:&e] : nil;
							CVPixelBufferRef r = outp ? [outp featureValueForName:ss->outName].imageBufferValue : NULL;
							if (backing) CVPixelBufferRelease(backing);  // (r is the backing when Core ML used it; retained below)
							if (r && getenv("CADENCE_NN_DEBUG") && ss->runs % 200 == 0) {
								// (debug: the mean brightness of what went in and came out)
								double m[2] = {0, 0};
								CVPixelBufferRef bufs[2] = {in, r};
								for (int b = 0; b < 2; b++) {
									CVPixelBufferLockBaseAddress(bufs[b], kCVPixelBufferLock_ReadOnly);
									uint8_t *px = CVPixelBufferGetBaseAddress(bufs[b]);
									size_t row = CVPixelBufferGetBytesPerRow(bufs[b]), hh = CVPixelBufferGetHeight(bufs[b]), ww = CVPixelBufferGetWidth(bufs[b]);
									for (size_t y = 0; y < hh; y += 8) for (size_t x = 0; x < ww; x += 8) m[b] += px[y * row + x * 4 + 1];
									m[b] /= (double) ((hh / 8) * (ww / 8));
									CVPixelBufferUnlockBaseAddress(bufs[b], kCVPixelBufferLock_ReadOnly);
								}
								NSLog(@"cadence-style: in %.1f out %.1f (green mean) fmt %08x", m[0], m[1], (unsigned) CVPixelBufferGetPixelFormatType(r));
							}
							if (r) {
								CVPixelBufferRetain(r);
								os_unfair_lock_lock(&ss->lock);
								CVPixelBufferRef old = ss->latest;
								ss->latest = r;
								os_unfair_lock_unlock(&ss->lock);
								if (old) CVPixelBufferRelease(old);
								atomic_fetch_add(&ss->runs, 1);
								ss->lastMs = (CACurrentMediaTime() - t0) * 1000;
							}
							CVPixelBufferRelease(in);
							atomic_store(&ss->busy, 0);
						}
					});
				}];
			} else {
				if (in) CVPixelBufferRelease(in);
				atomic_store(&s->busy, 0);
			}
		} else {
			atomic_fetch_add(&s->skipped, 1);
		}
		// 2. the newest result over the frame
		os_unfair_lock_lock(&s->lock);
		CVPixelBufferRef latest = s->latest ? CVPixelBufferRetain(s->latest) : NULL;
		os_unfair_lock_unlock(&s->lock);
		if (latest && strength > 0) {
			CVMetalTextureRef tout = NULL;
			size_t w = CVPixelBufferGetWidth(latest), h = CVPixelBufferGetHeight(latest);
			id<MTLRenderPipelineState> p = overPipeline(s, color.pixelFormat);
			if (p && CVMetalTextureCacheCreateTextureFromImage(NULL, s->cache, latest, NULL, MTLPixelFormatBGRA8Unorm, w, h, 0, &tout) == kCVReturnSuccess) {
				MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
				rp.colorAttachments[0].texture = color;
				rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
				rp.colorAttachments[0].storeAction = MTLStoreActionStore;
				id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
				re.label = @"Cadence style (over the frame)";
				[re setRenderPipelineState:p];
				[re setFragmentTexture:CVMetalTextureGetTexture(tout) atIndex:0];
				float over[2] = {strength, (float) s->margin};
				[re setFragmentBytes:over length:8 atIndex:0];
				[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
				[re endEncoding];
				[cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
					CFRelease(tout);
					CVPixelBufferRelease(latest);
				}];
				drew = 1;
			} else {
				CVPixelBufferRelease(latest);
			}
		} else if (latest) {
			CVPixelBufferRelease(latest);
		}
		CVMetalTextureCacheFlush(s->cache, 0);
	}
	return drew;
}

// out: runs, frames skipped (model busy), the last run's milliseconds x 100
void mcst_stats(void *st, long *out) {
	NnStyle *s = st;
	out[0] = s->runs;
	out[1] = s->skipped;
	out[2] = (long) (s->lastMs * 100);
}

// Waits (up to 5 s) until no run is in flight: `busy` is set when a run is encoded and cleared at the end of the block its GPU
// completion handler queues, so once it's clear no handler can still reach the object. 0: still busy (a GPU hang): the caller
// leaks the object instead of freeing what a late handler would use.
static int nnIdle(_Atomic int *busy) {
	for (int i = 0; i < 5000; i++) {
		if (atomic_load(busy) == 0) return 1;
		usleep(1000);
	}
	return 0;
}

// Not the render thread with frames in flight: waits for the model's last run, then lets everything go.
void mcst_free(void *st) {
	NnStyle *s = st;
	if (!s) return;
	if (!nnIdle(&s->busy)) return;
	dispatch_sync(s->q, ^{
	});
	if (s->latest) CVPixelBufferRelease(s->latest);
	[s->model release];
	[s->inName release];
	[s->outName release];
	[s->down release];
	[s->vs release];
	[s->over release];
	for (int k = 0; k < 2; k++) [s->overPso[k] release];
	if (s->pool) CVPixelBufferPoolRelease(s->pool);
	if (s->outPool) CVPixelBufferPoolRelease(s->outPool);
	if (s->cache) CFRelease(s->cache);
	dispatch_release(s->q);
	free(s);
}

// ---- Smooth Textures (SmoothTextures.java): the block atlas's still sprites upscaled 2x once by VideoToolbox's low-latency
// super resolution (an ML model on the Neural Engine). Each sprite is upscaled on its own, wrapped (its 3 x 3 tiling, as it repeats
// on a wall), so neither its edges nor its neighbours in the atlas bleed into it; 25 at a time on a 256 x 256 sheet. The scaler
// takes and gives 4:2:0 video (luma at full size): colour goes there and back by BT.709 video range; alpha is the sprite's own,
// pixel for pixel (cutout edges stay as drawn). Animated sprites are left out (their frames change: SmoothTextures copies them).
#import <VideoToolbox/VideoToolbox.h>
// (the frame processors are macOS 26's: found by name at run time, vtfpLoad, so their availability is checked there)
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

static Class cVTLLSRConfig, cVTLLSRParams, cVTFrameProcessor, cVTFrameProcessorFrame;

static int vtfpLoad(char *err, int errCap) {
	if (cVTLLSRConfig) return 0;
	if (!dlopen("/System/Library/Frameworks/VideoToolbox.framework/VideoToolbox", RTLD_NOW | RTLD_LOCAL)) {
		snprintf(err, errCap, "can't open VideoToolbox: %s", dlerror());
		return -1;
	}
	cVTLLSRConfig = NSClassFromString(@"VTLowLatencySuperResolutionScalerConfiguration");
	cVTLLSRParams = NSClassFromString(@"VTLowLatencySuperResolutionScalerParameters");
	cVTFrameProcessor = NSClassFromString(@"VTFrameProcessor");
	cVTFrameProcessorFrame = NSClassFromString(@"VTFrameProcessorFrame");
	if (!cVTLLSRConfig || !cVTLLSRParams || !cVTFrameProcessor || !cVTFrameProcessorFrame || ![(id) cVTLLSRConfig isSupported]) {
		snprintf(err, errCap, "this Mac has no low-latency super resolution (macOS 26 and Apple silicon)");
		cVTLLSRConfig = nil;
		return -1;
	}
	return 0;
}

static inline uint8_t clamp8(float v) {
	return (uint8_t) (v < 0 ? 0 : v > 255 ? 255 : v + 0.5f);
}

// RGBA (w x h, rows top first) into a 420v pixel buffer: BT.709, video range.
static void rgbaTo420v(const uint8_t *rgba, int w, int h, CVPixelBufferRef pb) {
	CVPixelBufferLockBaseAddress(pb, 0);
	uint8_t *yp = CVPixelBufferGetBaseAddressOfPlane(pb, 0), *cp = CVPixelBufferGetBaseAddressOfPlane(pb, 1);
	size_t yr = CVPixelBufferGetBytesPerRowOfPlane(pb, 0), cr = CVPixelBufferGetBytesPerRowOfPlane(pb, 1);
	for (int y = 0; y < h; y++)
		for (int x = 0; x < w; x++) {
			const uint8_t *p = rgba + ((size_t) y * w + x) * 4;
			yp[y * yr + x] = clamp8(16 + 219 * (0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2]) / 255.0f);
		}
	for (int y = 0; y < h / 2; y++)
		for (int x = 0; x < w / 2; x++) {
			float r = 0, g = 0, b = 0;
			for (int k = 0; k < 4; k++) {
				const uint8_t *p = rgba + ((size_t) (2 * y + (k >> 1)) * w + 2 * x + (k & 1)) * 4;
				r += p[0]; g += p[1]; b += p[2];
			}
			r /= 1020.0f; g /= 1020.0f; b /= 1020.0f;
			float yy = 0.2126f * r + 0.7152f * g + 0.0722f * b;
			cp[y * cr + 2 * x] = clamp8(128 + 224 * (b - yy) / 1.8556f);
			cp[y * cr + 2 * x + 1] = clamp8(128 + 224 * (r - yy) / 1.5748f);
		}
	CVPixelBufferUnlockBaseAddress(pb, 0);
}

// One pixel of a 420v buffer back to RGB.
static void rgbAt420v(const uint8_t *yp, size_t yr, const uint8_t *cp, size_t cr, int x, int y, uint8_t *out) {
	float Y = (yp[y * yr + x] - 16) / 219.0f, Cb = (cp[(y / 2) * cr + (x / 2) * 2] - 128) / 224.0f, Cr = (cp[(y / 2) * cr + (x / 2) * 2 + 1] - 128) / 224.0f;
	float r = Y + 1.5748f * Cr, b = Y + 1.8556f * Cb, g = (Y - 0.2126f * r - 0.0722f * b) / 0.7152f;
	out[0] = clamp8(r * 255);
	out[1] = clamp8(g * 255);
	out[2] = clamp8(b * 255);
}

// A sprite's texel (wrapped inside the sprite); a transparent one takes the colour of an opaque neighbour (up to 2 away), so the
// model doesn't see the black under the transparent texels as an edge (BH7-08).
static const uint8_t *spriteTexel(const uint8_t *src, int w, int sx, int sy, int rw, int rh, int x, int y) {
	const uint8_t *p = src + ((size_t) (sy + y) * w + sx + x) * 4;
	if (p[3] >= 128) return p;
	static const int d[16][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
		{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 1}, {-2, -1}, {1, 2}, {-1, -2}};
	for (int k = 0; k < 16; k++) {
		int nx = x + d[k][0], ny = y + d[k][1];
		if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue;
		const uint8_t *q = src + ((size_t) (sy + ny) * w + sx + nx) * 4;
		if (q[3] >= 128) return q;
	}
	return p;
}

// src: the atlas's level 0 (RGBA, w x h, rows top first); out: 2w x 2h RGBA, already holding a 2x nearest copy (what isn't
// upscaled stays that). rects: n sprites (x, y, w, h in src pixels), the still ones. Returns the sprites upscaled, or -1 (err).
int mcsm_upscale(const uint8_t *src, int w, int h, uint8_t *out, const int *rects, int n, char *err, int errCap) {
	@autoreleasepool {
		if (vtfpLoad(err, errCap) != 0) return -1;
		const int S = 256;  // the sheet
		// (typed receivers: through a bare id the compiler may pass the float scale factor where the method doesn't read it)
		VTLowLatencySuperResolutionScalerConfiguration *cfg = [(VTLowLatencySuperResolutionScalerConfiguration *) [cVTLLSRConfig alloc]
			initWithFrameWidth:S frameHeight:S scaleFactor:2.0f];
		if (!cfg) { snprintf(err, errCap, "no 2x scaler for %dx%d", S, S); return -1; }
		[cfg autorelease];
		VTFrameProcessor *proc = [[(VTFrameProcessor *) [cVTFrameProcessor alloc] init] autorelease];
		NSError *e = nil;
		if (![proc startSessionWithConfiguration:cfg error:&e]) { snprintf(err, errCap, "start: %s", e.localizedDescription.UTF8String); return -1; }
		CVPixelBufferRef in = NULL, res = NULL;
		CVPixelBufferCreate(NULL, S, S, '420v', (CFDictionaryRef) [cfg sourcePixelBufferAttributes], &in);
		CVPixelBufferCreate(NULL, S * 2, S * 2, '420v', (CFDictionaryRef) [cfg destinationPixelBufferAttributes], &res);
		// what isn't upscaled (animated sprites, padding, sprites too big for a sheet) as a plain 2x copy
		for (int y = 0; y < h * 2; y++)
			for (int x = 0; x < w * 2; x++) memcpy(out + ((size_t) y * w * 2 + x) * 4, src + ((size_t) (y / 2) * w + x / 2) * 4, 4);
		uint8_t *sheet = calloc((size_t) S * S * 4, 1);
		int done = 0, frame = 0;
		for (int i = 0; i < n;) {
			// lay out as many wrapped sprites (3w x 3h each) as fit on the sheet, row by row
			int placed[64][3];  // sprite index, sheet x, sheet y
			int count = 0, cx = 0, cy = 0, rowH = 0;
			memset(sheet, 0, (size_t) S * S * 4);
			while (i < n && count < 64) {
				int rw = rects[i * 4 + 2], rh = rects[i * 4 + 3], tw = rw * 3, th = rh * 3;
				if (tw > S || th > S || rw < 1 || rh < 1) { i++; continue; }  // (too big for the sheet: stays nearest)
				if (cx + tw > S) { cx = 0; cy += rowH; rowH = 0; }
				if (cy + th > S) break;
				int sx = rects[i * 4], sy = rects[i * 4 + 1];
				for (int y = 0; y < th; y++)
					for (int x = 0; x < tw; x++) {
						const uint8_t *p = spriteTexel(src, w, sx, sy, rw, rh, x % rw, y % rh);
						uint8_t *q = sheet + ((size_t) (cy + y) * S + cx + x) * 4;
						q[0] = p[0]; q[1] = p[1]; q[2] = p[2]; q[3] = 255;
					}
				placed[count][0] = i;
				placed[count][1] = cx;
				placed[count][2] = cy;
				count++;
				cx += tw;
				if (th > rowH) rowH = th;
				i++;
			}
			if (count == 0) continue;
			rgbaTo420v(sheet, S, S, in);
			CMTime t = {frame++, 60, kCMTimeFlags_Valid, 0};
			VTFrameProcessorFrame *sf = [[(VTFrameProcessorFrame *) [cVTFrameProcessorFrame alloc] initWithBuffer:in presentationTimeStamp:t] autorelease];
			VTFrameProcessorFrame *df = [[(VTFrameProcessorFrame *) [cVTFrameProcessorFrame alloc] initWithBuffer:res presentationTimeStamp:t] autorelease];
			VTLowLatencySuperResolutionScalerParameters *params = [[(VTLowLatencySuperResolutionScalerParameters *) [cVTLLSRParams alloc]
				initWithSourceFrame:sf destinationFrame:df] autorelease];
			if (![proc processWithParameters:params error:&e]) { snprintf(err, errCap, "process: %s", e.localizedDescription.UTF8String); break; }
			// the middle copy of each, at twice the size, into the atlas
			CVPixelBufferLockBaseAddress(res, kCVPixelBufferLock_ReadOnly);
			const uint8_t *yp = CVPixelBufferGetBaseAddressOfPlane(res, 0), *cp = CVPixelBufferGetBaseAddressOfPlane(res, 1);
			size_t yr = CVPixelBufferGetBytesPerRowOfPlane(res, 0), cr = CVPixelBufferGetBytesPerRowOfPlane(res, 1);
			for (int k = 0; k < count; k++) {
				int si = placed[k][0], rx = rects[si * 4], ry = rects[si * 4 + 1], rw = rects[si * 4 + 2], rh = rects[si * 4 + 3];
				int ox = (placed[k][1] + rw) * 2, oy = (placed[k][2] + rh) * 2;
				// a cutout sprite (any texel not opaque: torches, plants, bars): its own colours kept, only the model's detail in
				// brightness added; the 4:2:0 chroma of a 2-texel-wide stick is its neighbours' (blue / green torch sticks, BH7-08)
				bool cutout = false;
				for (int y = 0; y < rh && !cutout; y++)
					for (int x = 0; x < rw; x++)
						if (src[((size_t) (ry + y) * w + rx + x) * 4 + 3] < 255) { cutout = true; break; }
				for (int y = 0; y < rh * 2; y++)
					for (int x = 0; x < rw * 2; x++) {
						uint8_t *q = out + ((size_t) (ry * 2 + y) * (w * 2) + rx * 2 + x) * 4;
						const uint8_t *s = src + ((size_t) (ry + y / 2) * w + rx + x / 2) * 4;
						if (cutout) {
							float ys = 0.2126f * s[0] + 0.7152f * s[1] + 0.0722f * s[2];
							float ym = 255 * (yp[(oy + y) * yr + ox + x] - 16) / 219.0f;
							float dl = ym - ys;
							dl = dl > 48 ? 48 : dl < -48 ? -48 : dl;  // (ringing kept small)
							q[0] = clamp8(s[0] + dl);
							q[1] = clamp8(s[1] + dl);
							q[2] = clamp8(s[2] + dl);
						} else {
							rgbAt420v(yp, yr, cp, cr, ox + x, oy + y, q);
						}
						q[3] = s[3];
					}
				done++;
			}
			CVPixelBufferUnlockBaseAddress(res, kCVPixelBufferLock_ReadOnly);
		}
		[proc endSession];
		free(sheet);
		CVPixelBufferRelease(in);
		CVPixelBufferRelease(res);
		return done;
	}
}

// Smooth Textures, each frame before the terrain is drawn: the animated sprites (rects: n of them, x y w h in the 1x atlas, an
// int4 each; maxW, maxH their largest size) copied from the game's atlas into the 2x one: level 0 twice the size (nn_anim),
// the 1x atlas's level k into the 2x one's level k + 1 (the same size: a blit).
static id<MTLComputePipelineState> animPso;
void mcsm_anim(Ctx *ctx, Enc *enc, id<MTLTexture> src, id<MTLTexture> dst, const int *rects, int n, int maxW, int maxH) {
	if (n <= 0 || !src || !dst) return;
	@autoreleasepool {
		if (!animPso) {
			NSError *e = nil;
			id<MTLLibrary> lib = [[ctx->device newLibraryWithSource:neuralSource options:[[MTLCompileOptions new] autorelease] error:&e] autorelease];
			id<MTLFunction> fn = [[lib newFunctionWithName:@"nn_anim"] autorelease];
			animPso = fn ? [ctx->device newComputePipelineStateWithFunction:fn error:&e] : nil;
			if (!animPso) { NSLog(@"cadence-smooth: %@", e); return; }
		}
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
		c.label = @"Cadence smooth textures (animated sprites)";
		[c setComputePipelineState:animPso];
		[c setTexture:src atIndex:0];
		[c setTexture:dst atIndex:1];
		// (setBytes takes at most 4 KB: 256 sprites; more go in a buffer, BH7-16)
		if (n * 16 <= 4096) [c setBytes:rects length:(NSUInteger) n * 16 atIndex:0];
		else [c setBuffer:[[ctx->device newBufferWithBytes:rects length:(NSUInteger) n * 16 options:MTLResourceStorageModeShared] autorelease] offset:0 atIndex:0];
		[c dispatchThreads:MTLSizeMake((NSUInteger) maxW * 2, (NSUInteger) maxH * 2, (NSUInteger) n) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		[c endEncoding];
		id<MTLBlitCommandEncoder> b = [cb blitCommandEncoder];
		NSUInteger levels = MIN(src.mipmapLevelCount, dst.mipmapLevelCount - 1);
		for (int i = 0; i < n; i++) {
			const int *r = rects + i * 4;
			for (NSUInteger k = 0; k < levels; k++) {
				NSUInteger x = (NSUInteger) r[0] >> k, y = (NSUInteger) r[1] >> k, w = MAX(1u, (NSUInteger) r[2] >> k), h = MAX(1u, (NSUInteger) r[3] >> k);
				if (x + w > MAX(1u, src.width >> k) || y + h > MAX(1u, src.height >> k)) continue;
				[b copyFromTexture:src sourceSlice:0 sourceLevel:k sourceOrigin:MTLOriginMake(x, y, 0) sourceSize:MTLSizeMake(w, h, 1)
					toTexture:dst destinationSlice:0 destinationLevel:k + 1 destinationOrigin:MTLOriginMake(x, y, 0)];
			}
		}
		[b endEncoding];
	}
}

// Smooth Textures: the 1x atlas's level k into the 2x atlas's level k + 1, every level (the same sizes).
void mcsm_copy_levels(Enc *enc, id<MTLTexture> src, id<MTLTexture> dst) {
	@autoreleasepool {
		id<MTLBlitCommandEncoder> b = [mc_frame_cmd(enc) blitCommandEncoder];
		NSUInteger levels = MIN(src.mipmapLevelCount, dst.mipmapLevelCount - 1);
		for (NSUInteger k = 0; k < levels; k++)
			[b copyFromTexture:src sourceSlice:0 sourceLevel:k toTexture:dst destinationSlice:0 destinationLevel:k + 1 sliceCount:1 levelCount:1];
		[b endEncoding];
	}
}

// ---- Neural Engine upscaler (NeuralUpscaler.java): the world drawn at a lower resolution (World Resolution), upscaled by
// VideoToolbox's low-latency super resolution on the Neural Engine instead of MetalFX on the GPU. The GPU only converts: the
// scaled world into a 4:2:0 picture (nn_to420), and the upscaled one back into the frame (nn_from420). The model runs between,
// on a serial queue once the GPU is done with the frame, so the world on screen is the one from the frame before (the hand
// and the HUD are the frame's own); one frame is in the model at a time.
#define NU_TILES 4
typedef struct {
	Ctx *ctx;
	VTFrameProcessor *proc;
	int inW, inH, outW, outH;
	// the picture in tiles when the scaler won't take it whole (it takes at most 1280 x 1280, and 2x only up to about 960 wide):
	// tilesX x tilesY of tileW x tileH, the second column and row starting at secondX, secondY (overlapping the first)
	int tilesX, tilesY, tileW, tileH, secondX, secondY;
	CVPixelBufferPoolRef inPool, outPool;
	CVMetalTextureCacheRef cache;
	id<MTLComputePipelineState> to420;
	id<MTLRenderPipelineState> from420[2];
	id<MTLFunction> vs, fs;
	dispatch_queue_t q;
	os_unfair_lock lock;
	CVPixelBufferRef latest[NU_TILES];
	_Atomic int busy;
	_Atomic long runs;
	_Atomic double lastMs;
	long frame;
} NnUp;

// Tiles by slot: 0 the first, 1 the second column, 2 the second row, 3 both (the slots a tiling doesn't use stay empty).
static int nuSlot(NnUp *u, int slot) {
	return (!(slot & 1) || u->tilesX == 2) && (!(slot & 2) || u->tilesY == 2);
}

static int nuOffers(int w, int h, float scale) {
	for (NSNumber *f in [cVTLLSRConfig supportedScaleFactorsForFrameWidth:w frameHeight:h]) if (fabsf(f.floatValue - scale) < 0.01f) return 1;
	return 0;
}

// in: the scaled world's size; scale: the factor (2 or 1.5). The picture whole if the scaler takes it, else in 2 or 4
// overlapping tiles (each half plus 32 pixels, even sizes). NULL (err) if no tiling works. Takes about a second the first time
// (Apple's model is compiled): not on the render thread.
void *mcnu_new(Ctx *ctx, int inW, int inH, float scale, char *err, int errCap) {
	@autoreleasepool {
		if (vtfpLoad(err, errCap) != 0) return NULL;
		int tx = 0, ty = 0, tw = 0, th = 0;
		for (int k = 0; k < 4 && !tx; k++) {
			int cx = k & 1 ? 2 : 1, cy = k & 2 ? 2 : 1;
			int w = cx == 1 ? inW : ((inW / 2 + 32) + 1) & ~1, h = cy == 1 ? inH : ((inH / 2 + 32) + 1) & ~1;
			if (w <= inW && h <= inH && nuOffers(w, h, scale)) { tx = cx; ty = cy; tw = w; th = h; }
		}
		if (!tx) { snprintf(err, errCap, "%dx%d can't be upscaled %.1fx, whole or in tiles", inW, inH, scale); return NULL; }
		VTLowLatencySuperResolutionScalerConfiguration *cfg = [[(VTLowLatencySuperResolutionScalerConfiguration *) [cVTLLSRConfig alloc]
			initWithFrameWidth:tw frameHeight:th scaleFactor:scale] autorelease];
		VTFrameProcessor *proc = [(VTFrameProcessor *) [cVTFrameProcessor alloc] init];
		NSError *e = nil;
		if (!cfg || ![proc startSessionWithConfiguration:cfg error:&e]) {
			snprintf(err, errCap, "start: %s", e ? e.localizedDescription.UTF8String : "no configuration");
			[proc release];
			return NULL;
		}
		NSError *le = nil;
		id<MTLLibrary> lib = [[ctx->device newLibraryWithSource:neuralSource options:[[MTLCompileOptions new] autorelease] error:&le] autorelease];
		id<MTLFunction> fn = [[lib newFunctionWithName:@"nn_to420"] autorelease];
		id<MTLComputePipelineState> to420 = fn ? [ctx->device newComputePipelineStateWithFunction:fn error:&le] : nil;
		if (!to420) { snprintf(err, errCap, "nn_to420: %s", le.localizedDescription.UTF8String); [proc endSession]; [proc release]; return NULL; }
		NnUp *u = calloc(1, sizeof(NnUp));
		u->ctx = ctx;
		u->proc = proc;
		u->inW = inW;
		u->inH = inH;
		u->outW = (int) lroundf(inW * scale);
		u->outH = (int) lroundf(inH * scale);
		u->tilesX = tx;
		u->tilesY = ty;
		u->tileW = tw;
		u->tileH = th;
		u->secondX = tx == 2 ? inW - tw : 0;
		u->secondY = ty == 2 ? inH - th : 0;
		u->to420 = to420;
		u->vs = [lib newFunctionWithName:@"nn_up_vs"];
		u->fs = [lib newFunctionWithName:@"nn_from420"];
		u->lock = OS_UNFAIR_LOCK_INIT;
		NSMutableDictionary *ia = [[cfg.sourcePixelBufferAttributes mutableCopy] autorelease], *oa = [[cfg.destinationPixelBufferAttributes mutableCopy] autorelease];
		ia[(id) kCVPixelBufferMetalCompatibilityKey] = @YES;
		oa[(id) kCVPixelBufferMetalCompatibilityKey] = @YES;
		int n = tx * ty;
		CVPixelBufferPoolCreate(NULL, (CFDictionaryRef) @{(id) kCVPixelBufferPoolMinimumBufferCountKey: @(2 * n)}, (CFDictionaryRef) ia, &u->inPool);
		CVPixelBufferPoolCreate(NULL, (CFDictionaryRef) @{(id) kCVPixelBufferPoolMinimumBufferCountKey: @(3 * n)}, (CFDictionaryRef) oa, &u->outPool);
		CVMetalTextureCacheCreate(NULL, NULL, ctx->device, NULL, &u->cache);
		u->q = dispatch_queue_create("cadence.neural-upscale", DISPATCH_QUEUE_SERIAL);
		dispatch_set_target_queue(u->q, dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0));
		return u;
	}
}

// The tiling chosen: tiles across, down (0 0 if nu is NULL).
void mcnu_tiles(void *nu, int *out) {
	NnUp *u = nu;
	out[0] = u ? u->tilesX : 0;
	out[1] = u ? u->tilesY : 0;
}

static id<MTLTexture> planeTexture(CVMetalTextureCacheRef cache, CVPixelBufferRef pb, int plane, MTLPixelFormat f, int write, CVMetalTextureRef *ref) {
	size_t w = CVPixelBufferGetWidthOfPlane(pb, (size_t) plane), h = CVPixelBufferGetHeightOfPlane(pb, (size_t) plane);
	NSDictionary *attrs = write ? @{(id) kCVMetalTextureUsage: @(MTLTextureUsageShaderWrite | MTLTextureUsageShaderRead)} : nil;
	if (CVMetalTextureCacheCreateTextureFromImage(NULL, cache, pb, (CFDictionaryRef) attrs, f, w, h, (size_t) plane, ref) != kCVReturnSuccess) return nil;
	return CVMetalTextureGetTexture(*ref);
}

// Render thread, at the upscale: src (the scaled world) sent to the model unless it's busy; the newest upscaled picture drawn
// into dst. 1 if a picture was drawn (0: none yet, or a float frame (HDR) whose highlights the 8-bit model would clip: the
// caller upscales this frame another way).
int mcnu_frame(void *nu, Enc *enc, id<MTLTexture> src, id<MTLTexture> dst) {
	NnUp *u = nu;
	if (!src || !dst || src.pixelFormat == MTLPixelFormatRGBA16Float) return 0;
	int drew = 0;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		if ((int) src.width == u->inW && (int) src.height == u->inH && atomic_exchange(&u->busy, 1) == 0) {
			CVPixelBufferRef ins[NU_TILES] = {NULL};
			CVMetalTextureRef refs[2 * NU_TILES] = {NULL};
			int ok = 1;
			id<MTLComputeCommandEncoder> c = nil;
			for (int k = 0; k < NU_TILES && ok; k++) {
				if (!nuSlot(u, k)) continue;
				id<MTLTexture> yt = nil, ct = nil;
				if (CVPixelBufferPoolCreatePixelBuffer(NULL, u->inPool, &ins[k]) == kCVReturnSuccess) {
					yt = planeTexture(u->cache, ins[k], 0, MTLPixelFormatR8Unorm, 1, &refs[2 * k]);
					ct = planeTexture(u->cache, ins[k], 1, MTLPixelFormatRG8Unorm, 1, &refs[2 * k + 1]);
				}
				if (!yt || !ct) {
					ok = 0;
					break;
				}
				if (!c) {
					c = [cb computeCommandEncoder];
					c.label = @"Cadence neural upscale (to 4:2:0)";
					[c setComputePipelineState:u->to420];
					[c setTexture:src atIndex:0];
				}
				uint32_t at[2] = {(uint32_t) ((k & 1) ? u->secondX : 0), (uint32_t) ((k & 2) ? u->secondY : 0)};
				[c setTexture:yt atIndex:1];
				[c setTexture:ct atIndex:2];
				[c setBytes:at length:8 atIndex:0];
				[c dispatchThreads:MTLSizeMake((NSUInteger) u->tileW / 2, (NSUInteger) u->tileH / 2, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
			}
			if (c) [c endEncoding];
			if (ok) {
				NnUp *uu = u;
				long frame = ++u->frame;
				CVPixelBufferRef in0 = ins[0], in1 = ins[1], in2 = ins[2], in3 = ins[3];
				CVMetalTextureRef r[2 * NU_TILES];
				memcpy(r, refs, sizeof r);
				CVMetalTextureRef r0 = r[0], r1 = r[1], r2 = r[2], r3 = r[3], r4 = r[4], r5 = r[5], r6 = r[6], r7 = r[7];
				[cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
					CVMetalTextureRef rr[8] = {r0, r1, r2, r3, r4, r5, r6, r7};
					for (int k = 0; k < 8; k++) if (rr[k]) CFRelease(rr[k]);
					dispatch_async(uu->q, ^{
						@autoreleasepool {
							double t0 = CACurrentMediaTime();
							CVPixelBufferRef inb[NU_TILES] = {in0, in1, in2, in3}, outb[NU_TILES] = {NULL};
							int all = 1;
							CMTime t = {frame, 60, kCMTimeFlags_Valid, 0};
							for (int k = 0; k < NU_TILES && all; k++) {
								if (!inb[k]) continue;
								CVPixelBufferPoolCreatePixelBuffer(NULL, uu->outPool, &outb[k]);
								VTFrameProcessorFrame *sf = [[(VTFrameProcessorFrame *) [cVTFrameProcessorFrame alloc] initWithBuffer:inb[k] presentationTimeStamp:t] autorelease];
								VTFrameProcessorFrame *df = outb[k] ? [[(VTFrameProcessorFrame *) [cVTFrameProcessorFrame alloc] initWithBuffer:outb[k] presentationTimeStamp:t] autorelease] : nil;
								NSError *e = nil;
								all = df && [uu->proc processWithParameters:[[(VTLowLatencySuperResolutionScalerParameters *) [cVTLLSRParams alloc]
									initWithSourceFrame:sf destinationFrame:df] autorelease] error:&e];
							}
							if (all) {
								CVPixelBufferRef old[NU_TILES];
								os_unfair_lock_lock(&uu->lock);
								for (int k = 0; k < NU_TILES; k++) {
									old[k] = uu->latest[k];
									uu->latest[k] = outb[k];
									outb[k] = NULL;
								}
								os_unfair_lock_unlock(&uu->lock);
								for (int k = 0; k < NU_TILES; k++) if (old[k]) CVPixelBufferRelease(old[k]);
								atomic_fetch_add(&uu->runs, 1);
								uu->lastMs = (CACurrentMediaTime() - t0) * 1000;
							}
							for (int k = 0; k < NU_TILES; k++) {
								if (outb[k]) CVPixelBufferRelease(outb[k]);
								if (inb[k]) CVPixelBufferRelease(inb[k]);
							}
							atomic_store(&uu->busy, 0);
						}
					});
				}];
			} else {
				for (int k = 0; k < 2 * NU_TILES; k++) if (refs[k]) CFRelease(refs[k]);
				for (int k = 0; k < NU_TILES; k++) if (ins[k]) CVPixelBufferRelease(ins[k]);
				atomic_store(&u->busy, 0);
			}
		}
		CVPixelBufferRef latest[NU_TILES];
		os_unfair_lock_lock(&u->lock);
		for (int k = 0; k < NU_TILES; k++) latest[k] = u->latest[k] ? CVPixelBufferRetain(u->latest[k]) : NULL;
		os_unfair_lock_unlock(&u->lock);
		if (latest[0]) {
			int k = dst.pixelFormat == MTLPixelFormatRGBA16Float ? 1 : 0;
			if (!u->from420[k]) {
				MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor new] autorelease];
				d.vertexFunction = u->vs;
				d.fragmentFunction = u->fs;
				d.colorAttachments[0].pixelFormat = dst.pixelFormat;
				NSError *e = nil;
				u->from420[k] = [u->ctx->device newRenderPipelineStateWithDescriptor:d error:&e];
			}
			CVMetalTextureRef refs[2 * NU_TILES] = {NULL};
			id<MTLTexture> planes[2 * NU_TILES] = {nil};
			int have = 1;
			for (int t = 0; t < NU_TILES; t++) {
				if (!nuSlot(u, t)) continue;
				planes[2 * t] = latest[t] ? planeTexture(u->cache, latest[t], 0, MTLPixelFormatR8Unorm, 0, &refs[2 * t]) : nil;
				planes[2 * t + 1] = latest[t] ? planeTexture(u->cache, latest[t], 1, MTLPixelFormatRG8Unorm, 0, &refs[2 * t + 1]) : nil;
				if (!planes[2 * t] || !planes[2 * t + 1]) have = 0;
			}
			if (u->from420[k] && have) {
				MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
				rp.colorAttachments[0].texture = dst;
				rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
				rp.colorAttachments[0].storeAction = MTLStoreActionStore;
				id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
				re.label = @"Cadence neural upscale (into the frame)";
				[re setRenderPipelineState:u->from420[k]];
				// (a tile's planes stand in for the missing ones: the shader picks only tiles that exist)
				for (int t = 0; t < NU_TILES; t++) {
					int from = nuSlot(u, t) ? t : 0;
					[re setFragmentTexture:planes[2 * from] atIndex:(NSUInteger) (2 * t)];
					[re setFragmentTexture:planes[2 * from + 1] atIndex:(NSUInteger) (2 * t + 1)];
				}
				float params[6] = {(float) u->inW, (float) u->inH, (float) u->tileW, (float) u->tileH, (float) u->secondX, (float) u->secondY};
				[re setFragmentBytes:params length:sizeof params atIndex:0];
				[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
				[re endEncoding];
				drew = 1;
			}
			CVMetalTextureRef rr0 = refs[0], rr1 = refs[1], rr2 = refs[2], rr3 = refs[3], rr4 = refs[4], rr5 = refs[5], rr6 = refs[6], rr7 = refs[7];
			CVPixelBufferRef l0 = latest[0], l1 = latest[1], l2 = latest[2], l3 = latest[3];
			[cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
				CVMetalTextureRef rr[8] = {rr0, rr1, rr2, rr3, rr4, rr5, rr6, rr7};
				for (int q = 0; q < 8; q++) if (rr[q]) CFRelease(rr[q]);
				CVPixelBufferRef ll[4] = {l0, l1, l2, l3};
				for (int q = 0; q < 4; q++) if (ll[q]) CVPixelBufferRelease(ll[q]);
			}];
		} else {
			for (int t = 0; t < NU_TILES; t++) if (latest[t]) CVPixelBufferRelease(latest[t]);
		}
		CVMetalTextureCacheFlush(u->cache, 0);
	}
	return drew;
}

void mcnu_stats(void *nu, long *out) {
	NnUp *u = nu;
	out[0] = u->runs;
	out[1] = (long) (u->lastMs * 100);
}

void mcnu_free(void *nu) {
	NnUp *u = nu;
	if (!u) return;
	if (!nnIdle(&u->busy)) return;
	dispatch_sync(u->q, ^{
	});
	[u->proc endSession];
	[u->proc release];
	for (int k = 0; k < NU_TILES; k++) if (u->latest[k]) CVPixelBufferRelease(u->latest[k]);
	if (u->inPool) CVPixelBufferPoolRelease(u->inPool);
	if (u->outPool) CVPixelBufferPoolRelease(u->outPool);
	if (u->cache) CFRelease(u->cache);
	[u->to420 release];
	[u->vs release];
	[u->fs release];
	for (int k = 0; k < 2; k++) [u->from420[k] release];
	dispatch_release(u->q);
	free(u);
}
