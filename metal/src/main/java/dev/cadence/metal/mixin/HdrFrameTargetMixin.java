package dev.cadence.metal.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence HDR (dev.cadence.metal.Hdr): marks the textures the game's frame is drawn into while they are made, so MetalDevice makes
 * their colour a float one: the main target's (resize) and Cadence's scaled world target ("Cadence world", WorldScale). Texture
 * labels can't tell them apart: the game passes them to the backend only with its GPU debugging on.
 */
@Mixin(RenderTarget.class)
abstract class HdrFrameTargetMixin {
	@Shadow @Final protected String label;

	@Inject(method = "createBuffers", at = @At("HEAD"))
	private void cadence$hdrBegin(int width, int height, CallbackInfo ci) {
		dev.cadence.metal.Hdr.creatingFrame = (Object) this instanceof MainTarget || "Cadence world".equals(this.label);
	}

	@Inject(method = "createBuffers", at = @At("RETURN"))
	private void cadence$hdrEnd(int width, int height, CallbackInfo ci) {
		dev.cadence.metal.Hdr.creatingFrame = false;
	}
}
