// The GPU's floor per frame on this Mac (no Minecraft): how many command buffers / render passes a second the GPU finishes,
// for the frame shapes a record-mode frame could take. Metal 3 queue, as the game's default.
// Build: clang -fobjc-arc -O2 -framework Metal -framework QuartzCore -framework AppKit -framework Foundation tools/gpu_frame_floor.m -o /tmp/gpu_frame_floor
#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>

static id<MTLTexture> target(id<MTLDevice> d, int w, int h) {
	MTLTextureDescriptor *t = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:w height:h mipmapped:NO];
	t.usage = MTLTextureUsageRenderTarget;
	t.storageMode = MTLStorageModePrivate;
	return [d newTextureWithDescriptor:t];
}

static double run(id<MTLCommandQueue> q, int frames, int passesPerCb, id<MTLTexture> t, int inFlight) {
	dispatch_semaphore_t sem = dispatch_semaphore_create(inFlight);
	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = t;
	rp.colorAttachments[0].loadAction = MTLLoadActionClear;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	double t0 = CACurrentMediaTime();
	id<MTLCommandBuffer> last = nil;
	for (int f = 0; f < frames; f++) {
		@autoreleasepool {
			dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER);
			id<MTLCommandBuffer> cb = [q commandBufferWithUnretainedReferences];
			for (int p = 0; p < passesPerCb; p++) [[cb renderCommandEncoderWithDescriptor:rp] endEncoding];
			[cb addCompletedHandler:^(id<MTLCommandBuffer> c) { dispatch_semaphore_signal(sem); }];
			[cb commit];
			last = cb;
		}
	}
	[last waitUntilCompleted];
	return frames / (CACurrentMediaTime() - t0);
}

static double runCompute(id<MTLCommandQueue> q, id<MTLComputePipelineState> pso, id<MTLTexture> t, int frames, int inFlight, int blit) {
	dispatch_semaphore_t sem = dispatch_semaphore_create(inFlight);
	double t0 = CACurrentMediaTime();
	id<MTLCommandBuffer> last = nil;
	for (int f = 0; f < frames; f++) {
		@autoreleasepool {
			dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER);
			id<MTLCommandBuffer> cb = [q commandBufferWithUnretainedReferences];
			if (blit) {
				id<MTLBlitCommandEncoder> b = [cb blitCommandEncoder];
				[b generateMipmapsForTexture:t];
				[b endEncoding];
			} else {
				id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
				[c setComputePipelineState:pso];
				[c setTexture:t atIndex:0];
				[c dispatchThreads:MTLSizeMake(t.width, t.height, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
				[c endEncoding];
			}
			[cb addCompletedHandler:^(id<MTLCommandBuffer> c) { dispatch_semaphore_signal(sem); }];
			[cb commit];
			last = cb;
		}
	}
	[last waitUntilCompleted];
	return frames / (CACurrentMediaTime() - t0);
}

int main(void) {
	@autoreleasepool {
		id<MTLDevice> d = MTLCreateSystemDefaultDevice();
		id<MTLCommandQueue> q = [d newCommandQueue];
		id<MTLTexture> big = target(d, 1920, 1080), mid = target(d, 960, 540), small = target(d, 320, 180), tiny = target(d, 64, 36);
		run(q, 2000, 1, small, 5);  // warm up
		printf("command buffers a second, one clear pass each, 5 in flight:\n");
		printf("  1920x1080 %.0f   960x540 %.0f   320x180 %.0f   64x36 %.0f\n", run(q, 20000, 1, big, 5), run(q, 20000, 1, mid, 5),
			run(q, 20000, 1, small, 5), run(q, 20000, 1, tiny, 5));
		printf("in flight (320x180): 2 %.0f   5 %.0f   10 %.0f   32 %.0f\n", run(q, 20000, 1, small, 2), run(q, 20000, 1, small, 5),
			run(q, 20000, 1, small, 10), run(q, 20000, 1, small, 32));
		double p8 = run(q, 5000, 8, small, 5);
		printf("8 passes per command buffer (320x180): %.0f command buffers = %.0f passes a second\n", p8, p8 * 8);
		double p0 = run(q, 20000, 0, small, 5);
		printf("empty command buffers: %.0f a second\n", p0);
		NSError *e = nil;
		id<MTLLibrary> lib = [d newLibraryWithSource:@"#include <metal_stdlib>\nusing namespace metal;\nkernel void k(texture2d<float, access::write> t [[texture(0)]], uint2 p [[thread_position_in_grid]]) { t.write(float4(p.x * 0.01, p.y * 0.01, 0.5, 1), p); }" options:nil error:&e];
		id<MTLComputePipelineState> cp = [d newComputePipelineStateWithFunction:[lib newFunctionWithName:@"k"] error:&e];
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:320 height:180 mipmapped:NO];
		td.usage = MTLTextureUsageShaderWrite;
		td.storageMode = MTLStorageModePrivate;
		id<MTLTexture> ct = [d newTextureWithDescriptor:td];
		td.width = 960; td.height = 540;
		id<MTLTexture> ct2 = [d newTextureWithDescriptor:td];
		printf("compute frames (one dispatch writing the image): 320x180 %.0f (5 in flight), %.0f (32 in flight); 960x540 %.0f (5), %.0f (32)\n",
			runCompute(q, cp, ct, 20000, 5, 0), runCompute(q, cp, ct, 20000, 32, 0), runCompute(q, cp, ct2, 20000, 5, 0), runCompute(q, cp, ct2, 20000, 32, 0));
	}
	return 0;
}
