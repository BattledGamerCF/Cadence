// Cadence: Frame Generation (dev.cadence.metal.framegen, framegen.metal). Per real frame a snapshot (the world's colour and
// each pixel's distance, before the hand and the GUI) into a ring of two; a generated frame is one full-screen draw into its
// own drawable, presented on the frame's command buffer (Smooth: before the real frame) or on a command buffer of its own
// between frames (Responsive: from the frame limiter's wait). Both kinds of present go through mc_present_drawable, so the
// display shows each at least FramePacing's minimum: real and generated frames alternate evenly.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#import <CoreVideo/CoreVideo.h>
#import <VideoToolbox/VideoToolbox.h>
#import <VideoToolbox/VTFrameProcessor.h>
#import <VideoToolbox/VTFrameProcessor_LowLatencyFrameInterpolation.h>
#include <stdatomic.h>
#import "mcmetal.h"

void mc_present_drawable(id<MTLCommandBuffer> b, id<CAMetalDrawable> drawable);

typedef struct {
	id<MTLComputePipelineState> snap, snapF;
	id<MTLFunction> vs, fs, fsHdr;
	id<MTLRenderPipelineState> gen;
	MTLPixelFormat genFormat;
	id<MTLTexture> color[2], dist[2];
	int w, h, cur;  // cur: the slot the last snapshot wrote
	long generated;
	double gpuSum;
	long gpuCount;
	// Neural: VideoToolbox's low-latency frame interpolation (Apple's model, on the Neural Engine) between the last two
	// snapshots, each also kept as a 4:2:0 frame of at most 1280 x 720 (the interpolator is ~4 ms there on an M2, ~15 ms at 1080p)
	atomic_int neural;  // 0 off, 1 starting (the model loads, ~1 s, off the render thread), 2 ready, -1 failed
	id vt;              // VTFrameProcessor
	int nw, nh, yuvOk[2];
	CVMetalTextureCacheRef cache;
	CVPixelBufferRef yuv[2], out;
	id<MTLTexture> yuvTex[2][2], outTex[2];  // [slot][plane], [plane]
	CVMetalTextureRef yuvRef[2][2], outRef[2];
	id<MTLComputePipelineState> toYuv;
	id<MTLFunction> fsN, fsNHdr;
	id<MTLRenderPipelineState> genN;
	MTLPixelFormat genNFormat;
	long neuralFrames;
} Fg;

static void copyErr(NSError *e, char *err, int cap) {
	if (err && cap > 0) strlcpy(err, e ? e.localizedDescription.UTF8String : "unknown error", cap);
}

void *mcfg_new(Ctx *ctx, const char *source, char *err, int errCap) {
	@autoreleasepool {
		NSError *e = nil;
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.mathMode = MTLMathModeFast;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) { copyErr(e, err, errCap); return NULL; }
		Fg *g = calloc(1, sizeof(Fg));
		id<MTLFunction> s = [[lib newFunctionWithName:@"fg_snapshot"] autorelease], sf = [[lib newFunctionWithName:@"fg_snapshot_f"] autorelease];
		g->snap = [ctx->device newComputePipelineStateWithFunction:s error:&e];
		if (g->snap) g->snapF = [ctx->device newComputePipelineStateWithFunction:sf error:&e];
		g->vs = [lib newFunctionWithName:@"fg_vs"];
		g->fs = [lib newFunctionWithName:@"fg_fs"];
		g->fsHdr = [lib newFunctionWithName:@"fg_fs_hdr"];
		g->fsN = [lib newFunctionWithName:@"fg_fs_neural"];
		g->fsNHdr = [lib newFunctionWithName:@"fg_fs_neural_hdr"];
		id<MTLFunction> ty = [[lib newFunctionWithName:@"fg_to_yuv"] autorelease];
		if (ty) g->toYuv = [ctx->device newComputePipelineStateWithFunction:ty error:nil];
		[lib release];
		if (!g->snap || !g->snapF || !g->vs || !g->fs || !g->fsHdr) {
			copyErr(e, err, errCap);
			[g->snap release];
			[g->snapF release];
			[g->vs release];
			[g->fs release];
			[g->fsHdr release];
			free(g);
			return NULL;
		}
		return g;
	}
}

static void dropTextures(Fg *g) {
	for (int k = 0; k < 2; k++) {
		[g->color[k] release];
		[g->dist[k] release];
		g->color[k] = g->dist[k] = nil;
	}
	g->w = g->h = 0;
}

static id<MTLTexture> tex(Ctx *ctx, MTLPixelFormat f, int w, int h) {
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:w height:h mipmapped:NO];
	d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
	d.storageMode = MTLStorageModePrivate;
	return [ctx->device newTextureWithDescriptor:d];
}

// ---- Neural ----

static CVPixelBufferRef yuvBuffer(Fg *g, int w, int h) {
	NSDictionary *attrs = @{(id) kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange), (id) kCVPixelBufferWidthKey: @(w),
		(id) kCVPixelBufferHeightKey: @(h), (id) kCVPixelBufferIOSurfacePropertiesKey: @{}, (id) kCVPixelBufferMetalCompatibilityKey: @YES};
	CVPixelBufferRef pb = NULL;
	CVPixelBufferCreate(NULL, (size_t) w, (size_t) h, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, (CFDictionaryRef) attrs, &pb);
	return pb;
}

static id<MTLTexture> planeOf(Fg *g, CVPixelBufferRef pb, int plane, CVMetalTextureRef *ref) {
	MTLPixelFormat f = plane == 0 ? MTLPixelFormatR8Unorm : MTLPixelFormatRG8Unorm;
	size_t w = CVPixelBufferGetWidthOfPlane(pb, (size_t) plane), h = CVPixelBufferGetHeightOfPlane(pb, (size_t) plane);
	if (CVMetalTextureCacheCreateTextureFromImage(NULL, g->cache, pb, NULL, f, w, h, (size_t) plane, ref) != kCVReturnSuccess) return nil;
	return CVMetalTextureGetTexture(*ref);
}

// Everything Neural made let go (its frames may still be in flight: the processor and buffers go a second later).
static void neuralDrop(Fg *g) {
	int was = atomic_exchange(&g->neural, 0);
	if (was == 0 && !g->vt && !g->cache) return;
	id vt = g->vt;
	// (CF objects to release later: the texture refs first, then the buffers; a block can't capture an array)
	const void **cf = calloc(9, sizeof(void *));
	const void *list[9] = {g->yuvRef[0][0], g->yuvRef[0][1], g->yuvRef[1][0], g->yuvRef[1][1], g->outRef[0], g->outRef[1], g->yuv[0], g->yuv[1], g->out};
	memcpy(cf, list, sizeof list);
	CVMetalTextureCacheRef cache = g->cache;
	g->vt = nil;
	g->cache = NULL;
	memset(g->yuv, 0, sizeof g->yuv);
	g->out = NULL;
	memset(g->yuvTex, 0, sizeof g->yuvTex);
	memset(g->outTex, 0, sizeof g->outTex);
	memset(g->yuvRef, 0, sizeof g->yuvRef);
	memset(g->outRef, 0, sizeof g->outRef);
	g->yuvOk[0] = g->yuvOk[1] = 0;
	dispatch_after(dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC), dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
		if (vt) {
			if (@available(macOS 26.0, *)) [(VTFrameProcessor *) vt endSession];
			[vt release];
		}
		for (int i = 0; i < 9; i++) if (cf[i]) CFRelease(cf[i]);
		free(cf);
		if (cache) CFRelease(cache);
	});
}

// Neural wanted (on) or not, each generated frame's turn: on, the interpolator for the snapshots' size is started (its model loads
// on a queue of its own) and its frames made; off, let go. Returns 2 when ready, 1 while it starts, -1 if it can't run here.
int mcfg_neural(void *fg, Ctx *ctx, int on) {
	Fg *g = fg;
	int st = atomic_load(&g->neural);
	if (!on || !g->color[0]) {
		if (st != 0) neuralDrop(g);
		return 0;
	}
	if (st != 0) return st;
	if (@available(macOS 26.0, *)) {
	} else {
		atomic_store(&g->neural, -1);
		return -1;
	}
	if (!g->toYuv || !VTLowLatencyFrameInterpolationConfiguration.isSupported) {
		atomic_store(&g->neural, -1);
		return -1;
	}
	float k = fminf(1.0f, fminf(1280.0f / (float) g->w, 720.0f / (float) g->h));
	g->nw = ((int) ((float) g->w * k)) & ~1;
	g->nh = ((int) ((float) g->h * k)) & ~1;
	CVMetalTextureCacheCreate(NULL, NULL, ctx->device, NULL, &g->cache);
	for (int s = 0; s < 2; s++) {
		g->yuv[s] = yuvBuffer(g, g->nw, g->nh);
		for (int pl = 0; pl < 2; pl++) g->yuvTex[s][pl] = g->yuv[s] ? planeOf(g, g->yuv[s], pl, &g->yuvRef[s][pl]) : nil;
	}
	g->out = yuvBuffer(g, g->nw, g->nh);
	for (int pl = 0; pl < 2; pl++) g->outTex[pl] = g->out ? planeOf(g, g->out, pl, &g->outRef[pl]) : nil;
	if (!g->yuvTex[0][0] || !g->yuvTex[0][1] || !g->yuvTex[1][0] || !g->yuvTex[1][1] || !g->outTex[0] || !g->outTex[1]) {
		neuralDrop(g);
		atomic_store(&g->neural, -1);
		return -1;
	}
	atomic_store(&g->neural, 1);
	int w = g->nw, h = g->nh;
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		@autoreleasepool {
			VTLowLatencyFrameInterpolationConfiguration *cfg = [[[VTLowLatencyFrameInterpolationConfiguration alloc] initWithFrameWidth:w frameHeight:h
				numberOfInterpolatedFrames:1] autorelease];
			VTFrameProcessor *p = [VTFrameProcessor new];
			NSError *e = nil;
			if (cfg && [p startSessionWithConfiguration:cfg error:&e]) {
				// (still wanted: the render thread may have let it go meanwhile)
				int expected = 1;
				g->vt = p;
				if (!atomic_compare_exchange_strong(&g->neural, &expected, 2)) {
					g->vt = nil;
					[p endSession];
					[p release];
				}
			} else {
				NSLog(@"cadence-fg: neural interpolation: %@", e);
				[p release];
				int expected = 1;
				atomic_compare_exchange_strong(&g->neural, &expected, -1);
			}
		}
	});
	return 1;
}

static id<MTLRenderPipelineState> neuralPipeline(Fg *g, Ctx *ctx, MTLPixelFormat format) {
	if (g->genNFormat == format) return g->genN;
	@autoreleasepool {
		MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor new] autorelease];
		d.vertexFunction = g->vs;
		d.fragmentFunction = format == MTLPixelFormatRGBA16Float ? g->fsNHdr : g->fsN;
		d.colorAttachments[0].pixelFormat = format;
		NSError *e = nil;
		id<MTLRenderPipelineState> p = [ctx->device newRenderPipelineStateWithDescriptor:d error:&e];
		if (!p) NSLog(@"cadence-fg: neural pipeline: %@", e);
		[g->genN release];
		g->genN = p;
		g->genNFormat = format;
		return p;
	}
}

// The snapshot in slot s into its 4:2:0 frame for the interpolator (on the frame's command buffer, after the snapshot).
static void neuralSnapshot(Fg *g, id<MTLCommandBuffer> cb, int s) {
	if (atomic_load(&g->neural) != 2) {
		g->yuvOk[s] = 0;
		return;
	}
	id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
	c.label = @"Cadence frame generation (to the interpolator)";
	[c setComputePipelineState:g->toYuv];
	[c setTexture:g->color[s] atIndex:0];
	[c setTexture:g->yuvTex[s][0] atIndex:1];
	[c setTexture:g->yuvTex[s][1] atIndex:2];
	[c dispatchThreads:MTLSizeMake((NSUInteger) g->nw / 2, (NSUInteger) g->nh / 2, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	[c endEncoding];
	g->yuvOk[s] = 1;
}

// Neural's generated frame: Apple's interpolator halfway between the last two snapshots (on the command buffer: the GPU work
// after it waits for it), drawn into the drawable with the newest frame's hand and GUI. 0 if it can't now (then reprojection).
static int encodeNeural(Fg *g, Ctx *ctx, id<MTLCommandBuffer> cb, id<CAMetalDrawable> drawable, id<MTLTexture> latest, const void *frame, int len) {
	int a = g->cur, b = g->cur ^ 1;
	if (atomic_load(&g->neural) != 2 || !g->yuvOk[a] || !g->yuvOk[b]) return 0;
	id<MTLRenderPipelineState> p = neuralPipeline(g, ctx, drawable.texture.pixelFormat);
	if (!p) return 0;
	if (@available(macOS 26.0, *)) {
		long n = ++g->neuralFrames;
		VTFrameProcessorFrame *src = [[[VTFrameProcessorFrame alloc] initWithBuffer:g->yuv[a] presentationTimeStamp:CMTimeMake(n * 2, 120)] autorelease];
		VTFrameProcessorFrame *prev = [[[VTFrameProcessorFrame alloc] initWithBuffer:g->yuv[b] presentationTimeStamp:CMTimeMake(n * 2 - 2, 120)] autorelease];
		VTFrameProcessorFrame *dst = [[[VTFrameProcessorFrame alloc] initWithBuffer:g->out presentationTimeStamp:CMTimeMake(n * 2 - 1, 120)] autorelease];
		VTLowLatencyFrameInterpolationParameters *params = [[[VTLowLatencyFrameInterpolationParameters alloc] initWithSourceFrame:src previousFrame:prev
			interpolationPhase:@[@0.5f] destinationFrames:@[dst]] autorelease];
		if (!src || !prev || !dst || !params) return 0;
		[(VTFrameProcessor *) g->vt processWithCommandBuffer:cb parameters:params];
		const char *dump = getenv("CADENCE_FG_DUMP");
		if (dump && n == 90) {
			// (debug: the luma of the two snapshots and the frame made between them, as PGM files, once)
			CVPixelBufferRef bufs[3] = {g->yuv[b], g->out, g->yuv[a]};
			for (int i = 0; i < 3; i++) CVPixelBufferRetain(bufs[i]);
			CVPixelBufferRef b0 = bufs[0], b1 = bufs[1], b2 = bufs[2];
			char *base = strdup(dump);
			[cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
				for (int i = 0; i < 3; i++) {
					CVPixelBufferRef list[3] = {b0, b1, b2};
					CVPixelBufferLockBaseAddress(list[i], kCVPixelBufferLock_ReadOnly);
					size_t w = CVPixelBufferGetWidthOfPlane(list[i], 0), h = CVPixelBufferGetHeightOfPlane(list[i], 0), row = CVPixelBufferGetBytesPerRowOfPlane(list[i], 0);
					uint8_t *px = CVPixelBufferGetBaseAddressOfPlane(list[i], 0);
					char path[600];
					snprintf(path, sizeof path, "%s-%d.pgm", base, i);
					FILE *f = fopen(path, "wb");
					if (f) {
						fprintf(f, "P5\n%zu %zu\n255\n", w, h);
						for (size_t y = 0; y < h; y++) fwrite(px + (h - 1 - y) * row, 1, w, f);
						fclose(f);
					}
					CVPixelBufferUnlockBaseAddress(list[i], kCVPixelBufferLock_ReadOnly);
					CVPixelBufferRelease(list[i]);
				}
				free(base);
			}];
		}
	} else {
		return 0;
	}
	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = drawable.texture;
	rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
	r.label = @"Cadence frame generation (neural)";
	[r setRenderPipelineState:p];
	[r setFragmentBytes:frame length:len atIndex:0];
	if (drawable.texture.pixelFormat == MTLPixelFormatRGBA16Float) [r setFragmentBytes:mc_hdr_tone_now() length:sizeof(McTone) atIndex:1];
	[r setFragmentTexture:g->outTex[0] atIndex:0];
	[r setFragmentTexture:g->outTex[1] atIndex:1];
	[r setFragmentTexture:g->color[a] atIndex:2];
	[r setFragmentTexture:g->dist[a] atIndex:3];
	[r setFragmentTexture:latest atIndex:4];
	[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[r endEncoding];
	g->generated++;
	return 1;
}

// The snapshots' size (the presented frame's): made again when it changes. 0 x 0 lets them go (Frame Generation off).
void mcfg_size(void *fg, Ctx *ctx, int w, int h) {
	Fg *g = fg;
	if (g->w == w && g->h == h && (w == 0 || g->color[0])) return;
	neuralDrop(g);  // (made again for the new size)
	dropTextures(g);
	if (w <= 0 || h <= 0) return;
	for (int k = 0; k < 2; k++) {
		g->color[k] = tex(ctx, MTLPixelFormatRGBA8Unorm, w, h);
		g->dist[k] = tex(ctx, MTLPixelFormatR16Float, w, h);  // (half: ~0.05 blocks at 100; the sky is 60000)
	}
	g->w = w;
	g->h = h;
}

// A real frame's snapshot, on the frame's command buffer: color and depth (any size; sampled to the snapshot's), into the next
// slot. params: SnapParams (the projection inverted, the depth convention). Returns the slot written, -1 if none.
int mcfg_snapshot(void *fg, Enc *enc, id<MTLTexture> color, id<MTLTexture> depth, const void *params, int len) {
	Fg *g = fg;
	if (!g->color[0] || !color || !depth) return -1;
	if (g->color[0].pixelFormat != (color.pixelFormat == MTLPixelFormatRGBA16Float ? MTLPixelFormatRGBA16Float : MTLPixelFormatRGBA8Unorm)) {
		// (HDR turned on or off: the snapshots in the frame's format, so highlights above 1.0 survive)
		MTLPixelFormat f = color.pixelFormat == MTLPixelFormatRGBA16Float ? MTLPixelFormatRGBA16Float : MTLPixelFormatRGBA8Unorm;
		for (int k = 0; k < 2; k++) {
			[g->color[k] release];
			g->color[k] = tex(enc->ctx, f, g->w, g->h);
		}
	}
	int slot = g->cur ^ 1;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
		BOOL depthFormat = depth.pixelFormat == MTLPixelFormatDepth32Float || depth.pixelFormat == MTLPixelFormatDepth16Unorm
			|| depth.pixelFormat == MTLPixelFormatDepth32Float_Stencil8 || depth.pixelFormat == MTLPixelFormatDepth24Unorm_Stencil8;
		[c setComputePipelineState:depthFormat ? g->snap : g->snapF];
		[c setTexture:color atIndex:0];
		[c setTexture:depth atIndex:1];
		[c setTexture:g->color[slot] atIndex:2];
		[c setTexture:g->dist[slot] atIndex:3];
		[c setBytes:params length:len atIndex:0];
		[c dispatchThreads:MTLSizeMake(g->w, g->h, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		[c endEncoding];
		neuralSnapshot(g, cb, slot);
	}
	g->cur = slot;
	return slot;
}

static id<MTLRenderPipelineState> genPipeline(Fg *g, Ctx *ctx, MTLPixelFormat format) {
	if (g->genFormat == format) return g->gen;  // (nil: this format failed before)
	@autoreleasepool {
		MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor new] autorelease];
		d.vertexFunction = g->vs;
		d.fragmentFunction = format == MTLPixelFormatRGBA16Float ? g->fsHdr : g->fs;  // (the HDR layer's drawable: tone mapped)
		d.colorAttachments[0].pixelFormat = format;
		NSError *e = nil;
		id<MTLRenderPipelineState> p = [ctx->device newRenderPipelineStateWithDescriptor:d error:&e];
		if (!p) {
			// (once: the format is remembered, so a failing one isn't compiled and logged again every frame)
			NSLog(@"cadence-fg: pipeline: %@", e);
			[g->gen release];
			g->gen = nil;
			g->genFormat = format;
			return nil;
		}
		[g->gen release];
		g->gen = p;
		g->genFormat = format;
		return p;
	}
}

// Returns whether the frame was drawn (no pipeline for the drawable's format: nothing, and nothing to present).
static int encodeGen(Fg *g, Ctx *ctx, id<MTLCommandBuffer> cb, id<CAMetalDrawable> drawable, id<MTLTexture> latest, const void *frame, int len) {
	id<MTLRenderPipelineState> p = genPipeline(g, ctx, drawable.texture.pixelFormat);
	if (!p) return 0;
	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = drawable.texture;
	rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
	[r setRenderPipelineState:p];
	int a = g->cur, b = g->cur ^ 1;
	[r setFragmentBytes:frame length:len atIndex:0];
	if (drawable.texture.pixelFormat == MTLPixelFormatRGBA16Float) [r setFragmentBytes:mc_hdr_tone_now() length:sizeof(McTone) atIndex:1];
	[r setFragmentTexture:g->color[a] atIndex:0];
	[r setFragmentTexture:g->dist[a] atIndex:1];
	[r setFragmentTexture:g->color[b] atIndex:2];
	[r setFragmentTexture:g->dist[b] atIndex:3];
	[r setFragmentTexture:latest atIndex:4];
	[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[r endEncoding];
	g->generated++;
	return 1;
}

// Smooth: the generated frame drawn and presented on the frame's command buffer, before the real frame's own present.
// Takes the drawable (a +1 reference from mc_layer_next): released once the GPU is done with it.
void mcfg_present_in_frame(void *fg, Enc *enc, id<CAMetalDrawable> drawable, id<MTLTexture> latest, const void *frame, int len) {
	Fg *g = fg;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		if (g->color[0] && latest && (encodeNeural(g, enc->ctx, cb, drawable, latest, frame, len) || encodeGen(g, enc->ctx, cb, drawable, latest, frame, len)))
			mc_present_drawable(cb, drawable);
		[cb addCompletedHandler:^(id<MTLCommandBuffer> done) { [drawable release]; }];
	}
}

// Responsive: the generated frame on a command buffer of its own, committed at once (the frame limiter's wait, between frames).
void mcfg_present_alone(void *fg, Ctx *ctx, id<CAMetalDrawable> drawable, id<MTLTexture> latest, const void *frame, int len) {
	Fg *g = fg;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
		if (g->color[0] && latest && encodeGen(g, ctx, cb, drawable, latest, frame, len)) mc_present_drawable(cb, drawable);
		[cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
			if (done.GPUEndTime > done.GPUStartTime) {
				g->gpuSum += (done.GPUEndTime - done.GPUStartTime) * 1e6;
				g->gpuCount++;
			}
			[drawable release];
		}];
		[cb commit];
	}
}

// Measurement (the self-test's fgcheck): the generated frame from slot src alone (Responsive, no older frame) for the camera
// in frame, drawn offscreen; it and the other slot's snapshot (the real frame at that camera) read into out (2 x w x h RGBA8,
// generated first). Waits for the GPU. Returns 0, or -1 without snapshots.
int mcfg_debug(void *fg, Ctx *ctx, int src, const void *frame, int len, void *out) {
	Fg *g = fg;
	if (!g->color[0] || src < 0 || src > 1) return -1;
	@autoreleasepool {
		MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:g->w height:g->h mipmapped:NO];
		d.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
		d.storageMode = MTLStorageModePrivate;
		id<MTLTexture> o = [[ctx->device newTextureWithDescriptor:d] autorelease];
		id<MTLRenderPipelineState> p = genPipeline(g, ctx, MTLPixelFormatRGBA8Unorm);
		if (!p) return -1;
		NSUInteger bytes = (NSUInteger) g->w * g->h * 4;
		id<MTLBuffer> rb = [[ctx->device newBufferWithLength:bytes * 2 options:MTLResourceStorageModeShared] autorelease];
		id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = o;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
		[r setRenderPipelineState:p];
		[r setFragmentBytes:frame length:len atIndex:0];
		[r setFragmentTexture:g->color[src] atIndex:0];
		[r setFragmentTexture:g->dist[src] atIndex:1];
		[r setFragmentTexture:g->color[src ^ 1] atIndex:2];
		[r setFragmentTexture:g->dist[src ^ 1] atIndex:3];
		[r setFragmentTexture:g->color[src] atIndex:4];  // (no overlay: the newest frame is the source itself)
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
		id<MTLBlitCommandEncoder> bl = [cb blitCommandEncoder];
		[bl copyFromTexture:o sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(g->w, g->h, 1)
			toBuffer:rb destinationOffset:0 destinationBytesPerRow:g->w * 4 destinationBytesPerImage:bytes];
		[bl copyFromTexture:g->color[src ^ 1] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(g->w, g->h, 1)
			toBuffer:rb destinationOffset:bytes destinationBytesPerRow:g->w * 4 destinationBytesPerImage:bytes];
		[bl endEncoding];
		[cb commit];
		[cb waitUntilCompleted];
		memcpy(out, rb.contents, bytes * 2);
	}
	return 0;
}

long mcfg_generated(void *fg) {
	return ((Fg *) fg)->generated;
}

// The GPU time of a generated frame (Responsive's own command buffers), microseconds averaged since the last read; -1: none.
double mcfg_gpu_us(void *fg) {
	Fg *g = fg;
	long n = g->gpuCount;
	double s = g->gpuSum;
	g->gpuCount = 0;
	g->gpuSum = 0;
	return n > 0 ? s / n : -1;
}

void mcfg_free(void *fg) {
	Fg *g = fg;
	if (!g) return;
	neuralDrop(g);
	dropTextures(g);
	[g->toYuv release];
	[g->fsN release];
	[g->fsNHdr release];
	[g->genN release];
	[g->snap release];
	[g->snapF release];
	[g->vs release];
	[g->fs release];
	[g->fsHdr release];
	[g->gen release];
	free(g);
}
