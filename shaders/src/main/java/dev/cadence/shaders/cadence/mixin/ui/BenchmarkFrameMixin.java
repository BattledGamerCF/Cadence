package dev.cadence.shaders.cadence.mixin.ui;

import dev.cadence.shaders.cadence.benchmark.Benchmark;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (Benchmark): once per frame, just before the camera is set up, so the view Benchmark turns is the one drawn in
 * that very frame (the mouse is handled earlier in the frame, so nothing the player does can move it).
 */
@Mixin(GameRenderer.class)
public abstract class BenchmarkFrameMixin {
	@Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
	private void cadence$benchmarkFrame(DeltaTracker deltaTracker, CallbackInfo ci) {
		Benchmark.frame();
	}
}
