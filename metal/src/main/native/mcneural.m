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
