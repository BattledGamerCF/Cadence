package dev.cadence.metal.mixin.logic;

import dev.cadence.metal.logic.TickRange;
import net.minecraft.server.level.SimulationChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence Logic {@code tickRange} (TickRange): the version moves at every simulation-level change. */
@Mixin(SimulationChunkTracker.class)
abstract class TickRangeTrackerMixin {
	@Inject(method = "setLevel", at = @At("HEAD"))
	private void cadence$levelChanged(long chunk, int level, CallbackInfo ci) {
		TickRange.version++;
	}
}
