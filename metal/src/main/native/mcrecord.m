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

typedef struct {
	id<MTLComputePipelineState> trace;
	id<MTLTexture> color[3], depth[3], mid;  // the last three frames (this one, and the two the in-between frame is made from)
	int w, h, cur;
	long frames;
	void *lastCb;               // (not retained: only compared) the command buffer of the last frame
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
		[lib release];
		if (!r->trace) {
			copyErr(e, err, errCap);
			free(r);
			return NULL;
		}
		return r;
	}
}

static id<MTLTexture> tex(Ctx *ctx, MTLPixelFormat f, int w, int h) {
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:w height:h mipmapped:NO];
	d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
	d.storageMode = MTLStorageModePrivate;
	return [ctx->device newTextureWithDescriptor:d];
}

static void dropTextures(Rec *r) {
	for (int k = 0; k < 3; k++) {
		if (mc4On) { mc4_unpin(r->color[k]); mc4_unpin(r->depth[k]); }
		[r->color[k] release];
		[r->depth[k] release];
		r->color[k] = r->depth[k] = nil;
	}
	if (mc4On) mc4_unpin(r->mid);
	[r->mid release];
	r->mid = nil;
}

// The output size (the record resolution): textures made again when it changes.
void mcrm_size(void *rec, Ctx *ctx, int w, int h) {
	Rec *r = rec;
	if (r->w == w && r->h == h && r->color[0]) return;
	dropTextures(r);
	for (int k = 0; k < 3; k++) {
		r->color[k] = tex(ctx, MTLPixelFormatRGBA8Unorm, w, h);
		r->depth[k] = tex(ctx, MTLPixelFormatR32Float, w, h);
	}
	r->mid = tex(ctx, MTLPixelFormatRGBA8Unorm, w, h);
	r->w = w;
	r->h = h;
	r->frames = 0;
}


// One frame on the frame's command buffer: one dispatch traces it and, with interpolation on (the game passes it in the
// frame's flags), makes the in-between frame of the last two. Returns the texture to present: this frame's colour.
id<MTLTexture> mcrm_frame(void *rec, Enc *enc, const void *frame, int frameLength, id<MTLBuffer> grid, id<MTLBuffer> pal,
	id<MTLBuffer> entities, id<MTLBuffer> hud, id<MTLBuffer> occ, id<MTLBuffer> light, id<MTLBuffer> cells, id<MTLTexture> atlas, int interpolate) {
	(void) interpolate;  // (in the frame's flags)
	Rec *r = rec;
	if (!r->color[0]) return nil;
	int cur = r->cur, a = (cur + 2) % 3, b = (cur + 1) % 3;  // (a: the last frame, b: the one before)
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		id<MTLComputeCommandEncoder> c = [cb computeCommandEncoderWithDispatchType:MTLDispatchTypeSerial];
		[c setComputePipelineState:r->trace];
		[c setTexture:r->color[cur] atIndex:0];
		[c setTexture:r->depth[cur] atIndex:1];
		[c setTexture:atlas atIndex:2];
		[c setTexture:r->color[a] atIndex:4];
		[c setTexture:r->depth[a] atIndex:5];
		[c setTexture:r->color[b] atIndex:6];
		[c setTexture:r->depth[b] atIndex:7];
		[c setTexture:r->mid atIndex:8];
		[c setBytes:frame length:frameLength atIndex:0];
		[c setBuffer:grid offset:0 atIndex:1];
		[c setBuffer:pal offset:0 atIndex:3];
		[c setBuffer:entities offset:0 atIndex:4];
		[c setBuffer:hud offset:0 atIndex:5];
		[c setBuffer:occ offset:0 atIndex:6];
		[c setBuffer:light offset:0 atIndex:7];
		[c setBuffer:entities offset:32 * 48 atIndex:8];  // (the entity tiles' masks, after the boxes: Record.ENTITY_REGION)
		[c setBuffer:cells offset:0 atIndex:9];           // (the height field's cells)
		[c dispatchThreads:MTLSizeMake(r->w, r->h, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		[c endEncoding];
		if ((void *) cb != r->lastCb) {
			// the first record frame in this command buffer: sampled when one is due (its time over its record frames)
			r->lastCb = (void *) cb;
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
	r->cur = (cur + 1) % 3;
	r->frames++;
	return r->color[cur];
}

void mcrm_release(void *rec) {
	Rec *r = rec;
	if (!r) return;
	dropTextures(r);
	[r->trace release];
	free(r);
}

// (testing) The latest frame's pixels (RGBA8, w x h) into dst: a copy to shared memory, waited for. 0: no frame yet.
int mcrm_read(void *rec, Ctx *ctx, void *dst) {
	Rec *r = rec;
	if (!r || !r->color[0] || r->frames == 0) return 0;
	id<MTLTexture> t = r->color[(r->cur + 2) % 3];
	@autoreleasepool {
		id<MTLBuffer> b = [ctx->device newBufferWithLength:(NSUInteger) r->w * r->h * 4 options:MTLResourceStorageModeShared];
		id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
		id<MTLBlitCommandEncoder> e = [cb blitCommandEncoder];
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
