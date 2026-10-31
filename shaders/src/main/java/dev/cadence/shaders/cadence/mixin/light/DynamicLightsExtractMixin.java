package dev.cadence.shaders.cadence.mixin.light;

import dev.cadence.shaders.cadence.render.DynamicLights;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence: this frame's dynamic light sources, gathered before anything is extracted (DynamicLights). */
@Mixin(LevelExtractor.class)
public abstract class DynamicLightsExtractMixin {
	@Inject(method = "extract", at = @At("HEAD"))
	private void cadence$dynamicLights(DeltaTracker delta, Camera camera, float partial, CallbackInfo callback) {
		DynamicLights.beginFrame(camera, partial);
	}
}
