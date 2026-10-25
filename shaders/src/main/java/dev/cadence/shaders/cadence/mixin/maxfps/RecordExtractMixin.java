package dev.cadence.shaders.cadence.mixin.maxfps;

import dev.cadence.metal.record.Record;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (Record Mode): nothing gathered for the game's own drawing (window, options, lightmap, camera, world, GUI render
 * states), which a record frame skips whole; it was ~10% of the render thread at 25k frames a second.
 */
@Mixin(value = GameRenderer.class, priority = 400)
abstract class RecordExtractMixin {
	@Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), cancellable = true)
	private void cadence$record(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		if (Record.skipExtract && Record.active() && !Record.screenOpen()) ci.cancel();  // (a screen needs the GUI's)
	}
}
