// Cadence bench: GPU cost of MetalFX spatial vs temporal upscaling on this Mac (no Minecraft).
//   clang -fobjc-arc -O2 -framework Metal -framework MetalFX -framework Foundation tools/mfx_bench.m -o /tmp/mfxb && /tmp/mfxb [outW outH scale]
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#include <stdio.h>
#include <stdlib.h>

static id<MTLTexture> tex(id<MTLDevice> d, MTLPixelFormat f, int w, int h, MTLTextureUsage u) {
	MTLTextureDescriptor *t = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:w height:h mipmapped:NO];
	t.usage = u; t.storageMode = MTLStorageModePrivate;
	return [d newTextureWithDescriptor:t];
}

int main(int argc, char **argv) {
	@autoreleasepool {
		int ow = argc > 2 ? atoi(argv[1]) : 2940, oh = argc > 2 ? atoi(argv[2]) : 1838;
		double s = argc > 3 ? atof(argv[3]) : 0.5;
		int iw = (int) (ow * s), ih = (int) (oh * s), frames = 200;
		id<MTLDevice> d = MTLCreateSystemDefaultDevice();
		id<MTLCommandQueue> q = [d newCommandQueue];
		MTLPixelFormat cf = MTLPixelFormatRGBA8Unorm;
		id<MTLTexture> in = tex(d, cf, iw, ih, MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget);
		id<MTLTexture> depth = tex(d, MTLPixelFormatDepth32Float, iw, ih, MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget);
		id<MTLTexture> motion = tex(d, MTLPixelFormatRG16Float, iw, ih, MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget);
		id<MTLTexture> out = tex(d, cf, ow, oh, MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsageRenderTarget);
		MTLFXSpatialScalerDescriptor *sd = [MTLFXSpatialScalerDescriptor new];
		sd.inputWidth = iw; sd.inputHeight = ih; sd.outputWidth = ow; sd.outputHeight = oh;
		sd.colorTextureFormat = cf; sd.outputTextureFormat = cf; sd.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
		id<MTLFXSpatialScaler> sp = [sd newSpatialScalerWithDevice:d];
		MTLFXTemporalScalerDescriptor *td = [MTLFXTemporalScalerDescriptor new];
		td.inputWidth = iw; td.inputHeight = ih; td.outputWidth = ow; td.outputHeight = oh;
		td.colorTextureFormat = cf; td.outputTextureFormat = cf; td.depthTextureFormat = MTLPixelFormatDepth32Float;
		td.motionTextureFormat = MTLPixelFormatRG16Float;
		id<MTLFXTemporalScaler> tp = [td newTemporalScalerWithDevice:d];
		if (!sp || !tp) { printf("scaler unavailable (spatial %p temporal %p)\n", sp, tp); return 1; }
		id<MTLTexture> sOut = tex(d, cf, ow, oh, sp.outputTextureUsage | MTLTextureUsageShaderRead);
		id<MTLTexture> tOut = tex(d, cf, ow, oh, tp.outputTextureUsage | MTLTextureUsageShaderRead);
		printf("device %s, %dx%d -> %dx%d\n", d.name.UTF8String, iw, ih, ow, oh);
		for (int round = 0; round < 4; round++) { int mode = round & 1;
			__block double sum = 0; __block int n = 0;
			id<MTLCommandBuffer> last = nil;
			for (int f = 0; f < frames; f++) {
				id<MTLCommandBuffer> cb = [q commandBuffer];
				if (mode == 0) {
					sp.colorTexture = in; sp.outputTexture = sOut;
					[sp encodeToCommandBuffer:cb];
				} else {
					tp.colorTexture = in; tp.depthTexture = depth; tp.motionTexture = motion; tp.outputTexture = tOut;
					tp.jitterOffsetX = (f % 8) / 8.0f - 0.5f; tp.jitterOffsetY = (f % 5) / 5.0f - 0.5f;
					tp.reset = f == 0;
					[tp encodeToCommandBuffer:cb];
				}
				[cb addCompletedHandler:^(id<MTLCommandBuffer> c) { if (n++ >= 20) sum += (c.GPUEndTime - c.GPUStartTime) * 1e3; }];
				[cb commit];
				[cb waitUntilCompleted];
				last = cb;
			}
			[last waitUntilCompleted];
			printf("%-9s %.3f ms GPU per frame\n", mode == 0 ? "spatial" : "temporal", sum / (n - 20));
		}
	}
	return 0;
}
