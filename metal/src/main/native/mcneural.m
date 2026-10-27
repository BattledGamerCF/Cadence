// Cadence: the Neural Engine features (dev.cadence.metal.neural). Core ML and VideoToolbox's ML processors run on the Mac's
// Neural Engine, beside the GPU: the GPU only converts pictures to and from their pixel buffers.
//
// NnStyle Filters (StyleFilter.java): a style transfer model (trained on this Mac by Cadence Helper with Create ML) run on the
// Neural Engine. Each frame the world (before the hand and the HUD) is scaled into the model's 512 x 512 input on the GPU; once
// the GPU is done, a serial queue runs the model; the newest result is drawn over the next frames' world, blended by strength.
// One frame is in the model at a time (about 28 ms on an M2): when it's busy the frame isn't sent, the last result stays.
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
	"kernel void nn_down(texture2d<float, access::sample> src [[texture(0)]], texture2d<float, access::write> dst [[texture(1)]], uint2 g [[thread_position_in_grid]]) {\n"
	"  uint w = dst.get_width(), h = dst.get_height(); if (g.x >= w || g.y >= h) return;\n"
	"  constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::linear);\n"
	"  float2 uv = (float2(g) + 0.5) / float2(w, h); uv.y = 1.0 - uv.y;\n"
	"  float2 px = 1.0 / float2(src.get_width(), src.get_height()), step = float2(src.get_width() / float(w), src.get_height() / float(h)) * px * 0.25;\n"
	"  float3 c = 0; for (int k = 0; k < 4; k++) c += src.sample(s, uv + float2((k & 1) ? step.x : -step.x, (k & 2) ? step.y : -step.y)).rgb;\n"
	"  dst.write(float4(saturate(c * 0.25), 1.0), g);\n"
	"}\n"
	// Smooth Textures: the animated sprites' current frames (level 0) copied into the 2x atlas, each pixel twice each way
	"kernel void nn_anim(texture2d<float, access::read> src [[texture(0)]], texture2d<float, access::write> dst [[texture(1)]],\n"
	"    device const int4 *rects [[buffer(0)]], uint3 g [[thread_position_in_grid]]) {\n"
	"  int4 r = rects[g.z]; if (int(g.x) >= r.z * 2 || int(g.y) >= r.w * 2) return;\n"
	"  dst.write(src.read(uint2(r.x + int(g.x) / 2, r.y + int(g.y) / 2)), uint2(r.x * 2 + int(g.x), r.y * 2 + int(g.y)));\n"
	"}\n"
	"struct V { float4 pos [[position]]; float2 uv; };\n"
	"vertex V nn_vs(uint id [[vertex_id]]) { float2 p = float2((id << 1) & 2, id & 2); V v; v.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); v.uv = float2(p.x, 1.0 - p.y); return v; }\n"
	// the model's output (rows top first) over the frame (rows bottom first: the pass's y is flipped by the game's row order)
	"fragment float4 nn_over(V v [[stage_in]], texture2d<float> t [[texture(0)]], constant float &strength [[buffer(0)]]) {\n"
	"  constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::linear);\n"
	"  return float4(t.sample(s, float2(v.uv.x, 1.0 - v.uv.y)).rgb, strength);\n"
	"}\n";

typedef struct {
	Ctx *ctx;
	MLModel *model;
	NSString *inName, *outName;
	int inW, inH;
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
void *mcst_new(Ctx *ctx, const char *modelPath, const char *compiledPath, char *err, int errCap) {
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
		if (atomic_exchange(&s->busy, 1) == 0) {
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
				[re setFragmentBytes:&strength length:4 atIndex:0];
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

// Not the render thread with frames in flight: waits for the model's last run, then lets everything go.
void mcst_free(void *st) {
	NnStyle *s = st;
	if (!s) return;
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
						const uint8_t *p = src + ((size_t) (sy + y % rh) * w + sx + x % rw) * 4;
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
				for (int y = 0; y < rh * 2; y++)
					for (int x = 0; x < rw * 2; x++) {
						uint8_t *q = out + ((size_t) (ry * 2 + y) * (w * 2) + rx * 2 + x) * 4;
						rgbAt420v(yp, yr, cp, cr, ox + x, oy + y, q);
						q[3] = src[((size_t) (ry + y / 2) * w + rx + x / 2) * 4 + 3];
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
		[c setBytes:rects length:(NSUInteger) n * 16 atIndex:0];
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
