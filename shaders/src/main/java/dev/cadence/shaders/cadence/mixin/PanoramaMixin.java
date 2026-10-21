package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.render.PanoramaTexture;
import net.minecraft.client.renderer.CubeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (PanoramaTexture): the panorama drawn only while its texture is loaded. */
@Mixin(CubeMap.class)
public abstract class PanoramaMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void cadence$panorama(float pitch, float yaw, CallbackInfo ci) {
		if (!PanoramaTexture.drawing()) ci.cancel();
	}
}
