package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.render.MaxFpsMode;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (MaxFpsMode): nothing of the world gathered for drawing (it won't be drawn). */
@Mixin(value = LevelExtractor.class, priority = 500)
public abstract class MaxFpsModeExtractMixin {
	@Inject(method = "extract", at = @At("HEAD"), cancellable = true)
	private void cadence$maxFps(CallbackInfo ci) {
		if (MaxFpsMode.on() || dev.cadence.metal.record.Record.active()) ci.cancel();
	}
}
