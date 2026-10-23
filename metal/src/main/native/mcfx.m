// Cadence: MetalFX spatial upscaling of the shader pack's reduced-size frame into the window-size main target.
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#import "mcmetal.h"

id<MTLCommandBuffer> mc_frame_cmd(Enc *enc);
void *mc4_fx_spatial_new(MTLFXSpatialScalerDescriptor *d);
void mc4_fx_spatial_encode(void *scaler, id<MTLCommandBuffer> facade, id<MTLTexture> in, id<MTLTexture> out);

// A scaler for in -> out at their sizes and formats (+1 retained), or 0 when this device/format pair isn't supported.
void *mc_fx_spatial_new(Ctx *ctx, id<MTLTexture> in, id<MTLTexture> out) {
	@autoreleasepool {
		if (![MTLFXSpatialScalerDescriptor supportsDevice:ctx->device]) return NULL;
		MTLFXSpatialScalerDescriptor *d = [[MTLFXSpatialScalerDescriptor new] autorelease];
		d.inputWidth = in.width;
		d.inputHeight = in.height;
		d.outputWidth = out.width;
		d.outputHeight = out.height;
		d.colorTextureFormat = in.pixelFormat;
		d.outputTextureFormat = out.pixelFormat;
		d.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
		if (mc4On) return mc4_fx_spatial_new(d);  // (Metal 4: an MTL4FX scaler, made with the Metal 4 compiler)
		id<MTLFXSpatialScaler> s = [d newSpatialScalerWithDevice:ctx->device];
		return (void *) s;
	}
}

// Encodes in -> out on the frame's command buffer (ending any open encoder). 0: out lacks a usage the scaler needs.
int mc_fx_spatial_encode(Enc *enc, void *scaler, id<MTLTexture> in, id<MTLTexture> out) {
	id<MTLFXSpatialScalerBase> base = (id<MTLFXSpatialScalerBase>) scaler;
	if ((out.usage & base.outputTextureUsage) != base.outputTextureUsage) return 0;
	if ((in.usage & base.colorTextureUsage) != base.colorTextureUsage) return 0;
	if (mc4On) {
		mc4_fx_spatial_encode(scaler, mc_frame_cmd(enc), in, out);
		return 1;
	}
	id<MTLFXSpatialScaler> s = (id<MTLFXSpatialScaler>) scaler;
	if ((out.usage & s.outputTextureUsage) != s.outputTextureUsage) return 0;
	if ((in.usage & s.colorTextureUsage) != s.colorTextureUsage) return 0;
	s.colorTexture = in;
	s.outputTexture = out;
	s.inputContentWidth = in.width;
	s.inputContentHeight = in.height;
	[s encodeToCommandBuffer:mc_frame_cmd(enc)];
	return 1;
}

void mc_fx_spatial_release(void *scaler) {
	[(id) scaler release];
}
