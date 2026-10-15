// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.startup;

import dev.cadence.metal.Startup;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * -Dcadence.startup.blockCache=parallel|digest|parallel-digest: Blocks' registry loop adds each state to the id map and builds its
 * cache. With parallel, the loop only collects the states (the id map order is unchanged); their caches are built in parallel by
 * {@link BootstrapCacheMixin} once Blocks' class initialisation has finished (from inside it, workers deadlock on its init lock).
 */
@Mixin(Blocks.class)
abstract class BlockCacheMixin {
	@Redirect(method = "lambda$static$429", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;initCache()V"))
	private static void cadence$defer(BlockState state) {
		if (Startup.parallelBlockCache()) Startup.deferInitCache(state);
		else state.initCache();
	}
}
