// Cadence: Frame Generation (dev.cadence.metal.framegen, framegen.metal). Per real frame a snapshot (the world's colour and
// each pixel's distance, before the hand and the GUI) into a ring of two; a generated frame is one full-screen draw into its
// own drawable, presented on the frame's command buffer (Smooth: before the real frame) or on a command buffer of its own
// between frames (Responsive: from the frame limiter's wait). Both kinds of present go through mc_present_drawable, so the
// display shows each at least FramePacing's minimum: real and generated frames alternate evenly.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#import "mcmetal.h"

void mc_present_drawable(id<MTLCommandBuffer> b, id<CAMetalDrawable> drawable);

typedef struct {
	id<MTLComputePipelineState> snap, snapF;
	id<MTLFunction> vs, fs;
	id<MTLRenderPipelineState> gen;
	MTLPixelFormat genFormat;
	id<MTLTexture> color[2], dist[2];
	int w, h, cur;  // cur: the slot the last snapshot wrote
	long generated;
	double gpuSum;
	long gpuCount;
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
		[lib release];
		if (!g->snap || !g->snapF) {
			copyErr(e, err, errCap);
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

// The snapshots' size (the presented frame's): made again when it changes. 0 x 0 lets them go (Frame Generation off).
void mcfg_size(void *fg, Ctx *ctx, int w, int h) {
	Fg *g = fg;
	if (g->w == w && g->h == h && (w == 0 || g->color[0])) return;
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
	}
	g->cur = slot;
	return slot;
}

static id<MTLRenderPipelineState> genPipeline(Fg *g, Ctx *ctx, MTLPixelFormat format) {
	if (g->gen && g->genFormat == format) return g->gen;
	@autoreleasepool {
		MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor new] autorelease];
		d.vertexFunction = g->vs;
		d.fragmentFunction = g->fs;
		d.colorAttachments[0].pixelFormat = format;
		NSError *e = nil;
		id<MTLRenderPipelineState> p = [ctx->device newRenderPipelineStateWithDescriptor:d error:&e];
		if (!p) {
			NSLog(@"cadence-fg: pipeline: %@", e);
			return nil;
		}
		[g->gen release];
		g->gen = p;
		g->genFormat = format;
		return p;
	}
}

static void encodeGen(Fg *g, Ctx *ctx, id<MTLCommandBuffer> cb, id<CAMetalDrawable> drawable, id<MTLTexture> latest, const void *frame, int len) {
	id<MTLRenderPipelineState> p = genPipeline(g, ctx, drawable.texture.pixelFormat);
	if (!p) return;
	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = drawable.texture;
	rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
	[r setRenderPipelineState:p];
	int a = g->cur, b = g->cur ^ 1;
	[r setFragmentBytes:frame length:len atIndex:0];
	[r setFragmentTexture:g->color[a] atIndex:0];
	[r setFragmentTexture:g->dist[a] atIndex:1];
	[r setFragmentTexture:g->color[b] atIndex:2];
	[r setFragmentTexture:g->dist[b] atIndex:3];
	[r setFragmentTexture:latest atIndex:4];
	[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[r endEncoding];
	g->generated++;
}

// Smooth: the generated frame drawn and presented on the frame's command buffer, before the real frame's own present.
// Takes the drawable (a +1 reference from mc_layer_next): released once the GPU is done with it.
void mcfg_present_in_frame(void *fg, Enc *enc, id<CAMetalDrawable> drawable, id<MTLTexture> latest, const void *frame, int len) {
	Fg *g = fg;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		if (g->color[0] && latest) {
			encodeGen(g, enc->ctx, cb, drawable, latest, frame, len);
			mc_present_drawable(cb, drawable);
		}
		[cb addCompletedHandler:^(id<MTLCommandBuffer> done) { [drawable release]; }];
	}
}

// Responsive: the generated frame on a command buffer of its own, committed at once (the frame limiter's wait, between frames).
void mcfg_present_alone(void *fg, Ctx *ctx, id<CAMetalDrawable> drawable, id<MTLTexture> latest, const void *frame, int len) {
	Fg *g = fg;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
		if (g->color[0] && latest) {
			encodeGen(g, ctx, cb, drawable, latest, frame, len);
			mc_present_drawable(cb, drawable);
		}
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
	dropTextures(g);
	[g->snap release];
	[g->snapF release];
	[g->vs release];
	[g->fs release];
	[g->gen release];
	free(g);
}
