package dev.cadence.metal.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;

/**
 * Cadence: Apple's MetalFX spatial upscaler, for the shader packs' render scale (an alternative to Cadence's shader
 * upscale). One scaler per input/output size and format pair, rebuilt when they change. Render thread only.
 */
public final class MetalFxUpscaler {
	private static long scaler;
	private static String key = "";
	private static boolean unsupported;

	private MetalFxUpscaler() {}

	/** Upscales all of {@code source} into all of {@code destination} on the current frame; false: not possible (use another way). */
	public static boolean encode(GpuTextureView source, GpuTextureView destination) {
		if (unsupported || !(source instanceof MetalTexture.View in) || !(destination instanceof MetalTexture.View out)) return false;
		var device = RenderSystem.getDevice();
		if (device == null || !(((FrontendCommandEncoder) device.createCommandEncoder()).backend() instanceof MetalEncoder encoder)) return false;
		var a = source.texture();
		var b = destination.texture();
		String k = a.getWidth(0) + "x" + a.getHeight(0) + ":" + a.getFormat() + ">" + b.getWidth(0) + "x" + b.getHeight(0) + ":" + b.getFormat()
			+ (Hdr.active() ? " hdr" : "");  // (HDR: the game's frame textures are float ones under the same Java format)
		if (!k.equals(key)) {
			release();
			scaler = Native.fxSpatialNew(encoder.ctx, in.handle, out.handle);
			key = k;
			if (scaler == 0) {
				unsupported = true;
				System.out.println("cadence-metal: MetalFX spatial scaling isn't available for " + k);
				return false;
			}
		}
		// (lazy clears: MetalFX reads the source and writes every destination pixel outside a render pass, so the source's deferred
		// clear is written first and the destination's is dropped; left pending, it ran at the next pass on the destination and
		// wiped the upscaled world: packs below 100% scale showed only the clear colour and the HUD)
		encoder.flushClear((MetalTexture) a);
		encoder.dropPendingClear((MetalTexture) b);
		if (Native.fxSpatialEncode(encoder.enc, scaler, in.handle, out.handle) == 0) {
			unsupported = true;
			System.out.println("cadence-metal: MetalFX can't write the main target (texture usage); using the shader upscale");
			return false;
		}
		return true;
	}

	public static void release() {
		if (scaler != 0) Native.fxSpatialRelease(scaler);
		scaler = 0;
		key = "";
	}
}
