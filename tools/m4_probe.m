// Metal 4 probe (no Minecraft): MetalFX frame interpolation through Metal 3 and Metal 4 (does it write its output, what
// it costs), and the CPU cost of submitting work on a Metal 3 vs a Metal 4 queue.
// Build: clang -fobjc-arc -O2 -framework Metal -framework MetalFX -framework Foundation tools/m4_probe.m -o /tmp/m4_probe
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#include <mach/mach_time.h>

static double now_ms(void) {
	static mach_timebase_info_data_t tb;
	if (tb.denom == 0) mach_timebase_info(&tb);
	return (double) mach_absolute_time() * tb.numer / tb.denom / 1e6;
}

static id<MTLTexture> tex(id<MTLDevice> d, MTLPixelFormat f, int w, int h, MTLTextureUsage u, MTLStorageMode s) {
	MTLTextureDescriptor *t = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:w height:h mipmapped:NO];
	t.usage = u | MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsageRenderTarget;
	t.storageMode = s;
	return [d newTextureWithDescriptor:t];
}

// RGBA16F filled with one colour (shared storage, CPU written)
static void fill(id<MTLTexture> t, float r, float g, float b) {
	int w = (int) t.width, h = (int) t.height;
	__fp16 *p = malloc((size_t) w * h * 8);
	for (int i = 0; i < w * h; i++) { p[i * 4] = r; p[i * 4 + 1] = g; p[i * 4 + 2] = b; p[i * 4 + 3] = 1; }
	[t replaceRegion:MTLRegionMake2D(0, 0, w, h) mipmapLevel:0 withBytes:p bytesPerRow:w * 8];
	free(p);
}

static void centre(id<MTLTexture> t, float out[4]) {
	__fp16 px[4];
	[t getBytes:px bytesPerRow:8 fromRegion:MTLRegionMake2D(t.width / 2, t.height / 2, 1, 1) mipmapLevel:0];
	for (int i = 0; i < 4; i++) out[i] = px[i];
}

typedef struct { id<MTLTexture> color, prev, depth, motion, out; } Set;

static Set makeSet(id<MTLDevice> d, int w, int h) {
	Set s;
	s.color = tex(d, MTLPixelFormatRGBA16Float, w, h, 0, MTLStorageModeShared);
	s.prev = tex(d, MTLPixelFormatRGBA16Float, w, h, 0, MTLStorageModeShared);
	s.depth = tex(d, MTLPixelFormatDepth32Float, w, h, 0, MTLStorageModePrivate);
	s.motion = tex(d, MTLPixelFormatRG16Float, w, h, 0, MTLStorageModeShared);
	s.out = tex(d, MTLPixelFormatRGBA16Float, w, h, 0, MTLStorageModeShared);
	fill(s.color, 0, 0, 1);   // this frame blue
	fill(s.prev, 1, 0, 0);    // last frame red
	fill(s.out, 0, 1, 0);     // sentinel green: still green = nothing written
	uint32_t *z = calloc((size_t) w * h, 4);
	[s.motion replaceRegion:MTLRegionMake2D(0, 0, w, h) mipmapLevel:0 withBytes:z bytesPerRow:w * 4];
	free(z);
	return s;
}

static MTLFXFrameInterpolatorDescriptor *descFor(int w, int h) {
	MTLFXFrameInterpolatorDescriptor *fd = [MTLFXFrameInterpolatorDescriptor new];
	fd.colorTextureFormat = MTLPixelFormatRGBA16Float;
	fd.outputTextureFormat = MTLPixelFormatRGBA16Float;
	fd.depthTextureFormat = MTLPixelFormatDepth32Float;
	fd.motionTextureFormat = MTLPixelFormatRG16Float;
	fd.inputWidth = fd.outputWidth = w;
	fd.inputHeight = fd.outputHeight = h;
	return fd;
}

static void setInputs(id<MTLFXFrameInterpolatorBase> fi, Set s, int w, int h) {
	fi.colorTexture = s.color;
	fi.prevColorTexture = s.prev;
	fi.depthTexture = s.depth;
	fi.motionTexture = s.motion;
	fi.outputTexture = s.out;
	fi.motionVectorScaleX = w * 0.5f;
	fi.motionVectorScaleY = h * 0.5f;
	fi.deltaTime = 1.0f / 120;
	fi.nearPlane = 0.05f;
	fi.farPlane = 1000;
	fi.fieldOfView = 70;
	fi.aspectRatio = (float) w / h;
	fi.depthReversed = YES;
}

int main(void) {
	@autoreleasepool {
		id<MTLDevice> d = MTLCreateSystemDefaultDevice();
		printf("device %s, Metal 4 queue %s\n", d.name.UTF8String, [d respondsToSelector:@selector(newMTL4CommandQueue)] ? "yes" : "no");
		printf("MetalFX frame interpolation: Metal 3 %s, Metal 4 %s\n", [MTLFXFrameInterpolatorDescriptor supportsDevice:d] ? "yes" : "no",
			[MTLFXFrameInterpolatorDescriptor supportsMetal4FX:d] ? "yes" : "no");
		id<MTLCommandQueue> q3 = [d newCommandQueue];
		int sizes[][2] = {{960, 540}, {1920, 1080}, {2940, 1912}};
		for (int si = 0; si < 3; si++) {
			int w = sizes[si][0], h = sizes[si][1];
			// ---- Metal 3 ----
			Set s = makeSet(d, w, h);
			id<MTLCommandBuffer> cb = [q3 commandBuffer];
			MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
			rp.depthAttachment.texture = s.depth;
			rp.depthAttachment.loadAction = MTLLoadActionClear;
			rp.depthAttachment.clearDepth = 0.5;
			rp.depthAttachment.storeAction = MTLStoreActionStore;
			[[cb renderCommandEncoderWithDescriptor:rp] endEncoding];
			[cb commit];
			[cb waitUntilCompleted];
			id<MTLFXFrameInterpolator> fi3 = [descFor(w, h) newFrameInterpolatorWithDevice:d];
			float px[4] = {-1, -1, -1, -1};
			if (fi3) {
				setInputs(fi3, s, w, h);
				cb = [q3 commandBuffer];
				[fi3 encodeToCommandBuffer:cb];
				[cb commit];
				[cb waitUntilCompleted];
				centre(s.out, px);
				// cost: 100 interpolations, GPU time of the batch
				fi3.shouldResetHistory = NO;
				double gpu = 0;
				for (int i = 0; i < 100; i++) {
					cb = [q3 commandBuffer];
					[fi3 encodeToCommandBuffer:cb];
					[cb commit];
					[cb waitUntilCompleted];
					gpu += (cb.GPUEndTime - cb.GPUStartTime) * 1000;
				}
				printf("%dx%d Metal 3 interpolator: output centre (%.2f %.2f %.2f) %s, %.3f ms GPU each\n", w, h, px[0], px[1], px[2],
					px[1] > 0.99f && px[0] < 0.01f ? "NOT WRITTEN" : "written", gpu / 100);
			} else printf("%dx%d Metal 3 interpolator: not created\n", w, h);
			// ---- Metal 4 ----
			Set s4 = makeSet(d, w, h);
			cb = [q3 commandBuffer];
			rp.depthAttachment.texture = s4.depth;
			[[cb renderCommandEncoderWithDescriptor:rp] endEncoding];
			[cb commit];
			[cb waitUntilCompleted];
			NSError *err = nil;
			id<MTL4Compiler> comp = [d newCompilerWithDescriptor:[MTL4CompilerDescriptor new] error:&err];
			id<MTL4FXFrameInterpolator> fi4 = comp ? [descFor(w, h) newFrameInterpolatorWithDevice:d compiler:comp] : nil;
			if (!fi4) {
				printf("%dx%d Metal 4 interpolator: not created (%s)\n", w, h, err.localizedDescription.UTF8String ?: "");
				continue;
			}
			setInputs(fi4, s4, w, h);
			id<MTL4CommandQueue> q4 = [d newMTL4CommandQueue];
			MTLResidencySetDescriptor *rd = [MTLResidencySetDescriptor new];
			id<MTLResidencySet> rs = [d newResidencySetWithDescriptor:rd error:&err];
			for (id<MTLTexture> t in @[s4.color, s4.prev, s4.depth, s4.motion, s4.out]) [rs addAllocation:t];
			[rs commit];
			[q4 addResidencySet:rs];
			id<MTL4CommandAllocator> alloc = [d newCommandAllocator];
			id<MTL4CommandBuffer> cb4 = [d newCommandBuffer];
			id<MTLSharedEvent> ev = [d newSharedEvent];
			uint64_t v = 0;
			double t0 = now_ms();
			for (int i = 0; i <= 100; i++) {
				[alloc reset];
				[cb4 beginCommandBufferWithAllocator:alloc];
				[fi4 encodeToCommandBuffer:cb4];
				[cb4 endCommandBuffer];
				id<MTL4CommandBuffer> list[1] = {cb4};
				[q4 commit:list count:1];
				[q4 signalEvent:ev value:++v];
				[ev waitUntilSignaledValue:v timeoutMS:5000];
				if (i == 0) {
					centre(s4.out, px);
					t0 = now_ms();
				}
			}
			printf("%dx%d Metal 4 interpolator: output centre (%.2f %.2f %.2f) %s, %.3f ms each (wall, incl. wait)\n", w, h, px[0], px[1], px[2],
				px[1] > 0.99f && px[0] < 0.01f ? "NOT WRITTEN" : "written", (now_ms() - t0) / 100);
		}
		// ---- submission cost: empty command buffers, 20,000 each way ----
		int N = 20000;
		double t0 = now_ms();
		id<MTLCommandBuffer> last = nil;
		for (int i = 0; i < N; i++) {
			@autoreleasepool {
				last = [q3 commandBufferWithUnretainedReferences];
				[last commit];
			}
		}
		[last waitUntilCompleted];
		double m3 = (now_ms() - t0) * 1000 / N;
		id<MTL4CommandQueue> q4 = [d newMTL4CommandQueue];
		id<MTLSharedEvent> ev = [d newSharedEvent];
		const int SLOTS = 3;
		id<MTL4CommandAllocator> al[SLOTS];
		id<MTL4CommandBuffer> cbs[SLOTS];
		for (int k = 0; k < SLOTS; k++) { al[k] = [d newCommandAllocator]; cbs[k] = [d newCommandBuffer]; }
		t0 = now_ms();
		for (uint64_t i = 1; i <= (uint64_t) N; i++) {
			int k = (int) (i % SLOTS);
			if (i > SLOTS) [ev waitUntilSignaledValue:i - SLOTS timeoutMS:5000];
			[al[k] reset];
			[cbs[k] beginCommandBufferWithAllocator:al[k]];
			[cbs[k] endCommandBuffer];
			id<MTL4CommandBuffer> list[1] = {cbs[k]};
			[q4 commit:list count:1];
			[q4 signalEvent:ev value:i];
		}
		[ev waitUntilSignaledValue:N timeoutMS:5000];
		double m4 = (now_ms() - t0) * 1000 / N;
		printf("empty submissions: Metal 3 %.2f us each (%.0f/s), Metal 4 %.2f us each (%.0f/s, 3 slots)\n", m3, 1e6 / m3, m4, 1e6 / m4);
	}
	return 0;
}
