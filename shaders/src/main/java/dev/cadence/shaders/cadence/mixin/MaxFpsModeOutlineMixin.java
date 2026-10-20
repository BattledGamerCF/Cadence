package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.MaxFpsMode;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (MaxFpsMode): GameRenderer.render still blits the glowing-entity outlines after the skipped renderLevel; their last frame's would show over the void. */
@Mixin(LevelRenderer.class)
public abstract class MaxFpsModeOutlineMixin {
	@Inject(method = "blitEntityOutline", at = @At("HEAD"), cancellable = true)
	private void cadence$maxFps(CallbackInfo ci) {
		if (MaxFpsMode.on()) ci.cancel();
	}
}
