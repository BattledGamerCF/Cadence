package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.mesh.MeshStateCache;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cadence (self-tests only): render-thread time spent building the chunk draw list each frame (A/Bs). */
@Mixin(LevelRenderer.class)
public abstract class DrawListTimingMixin {
	@Unique private static long cadence$start;

	@Inject(method = "prepareChunkRendersIndirect", at = @At("HEAD"))
	private void cadence$begin(CallbackInfoReturnable<?> cir) {
		if (MeshStateCache.TIMING) cadence$start = System.nanoTime();
	}

	@Inject(method = "prepareChunkRendersIndirect", at = @At("RETURN"))
	private void cadence$end(CallbackInfoReturnable<?> cir) {
		if (MeshStateCache.TIMING) MeshStateCache.drawList(System.nanoTime() - cadence$start);
	}
}
