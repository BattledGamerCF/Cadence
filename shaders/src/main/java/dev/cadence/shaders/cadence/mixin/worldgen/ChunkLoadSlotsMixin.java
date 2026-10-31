package dev.cadence.shaders.cadence.mixin.worldgen;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Cadence: how many chunks around a player vanilla brings up at once (its player-ticket throttler: 4). Each slot is a
 * chunk whose whole generation pyramid is being worked on; with 4, the worker pool sits mostly idle while flying into new
 * land. -Dcadence.chunkLoadSlots=N (1..64); unset: vanilla's 4. Changes only how many chunks load at once, not what they
 * contain or the order they are asked for (nearest first).
 */
@Mixin(DistanceManager.class)
public abstract class ChunkLoadSlotsMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/level/ThrottlingChunkTaskDispatcher;<init>(Lnet/minecraft/util/thread/TaskScheduler;Ljava/util/concurrent/Executor;I)V"),
		index = 2)
	private int cadence$slots(int vanilla) {
		String value = System.getProperty("cadence.chunkLoadSlots");
		if (value == null) return vanilla;
		try {
			return Math.max(1, Math.min(64, Integer.parseInt(value.strip())));
		} catch (NumberFormatException e) {
			return vanilla;
		}
	}
}
