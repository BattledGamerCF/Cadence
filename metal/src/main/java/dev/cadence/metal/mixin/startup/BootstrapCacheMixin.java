// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.startup;

import dev.cadence.metal.Startup;
import net.minecraft.server.Bootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dcadence.startup.blockCache=...: right after FireBlock.bootStrap() (which first touches Blocks, so Blocks is initialised when it
 * returns; it only sets flammability, no state cache), the deferred state caches are built, before anything else in Bootstrap runs.
 */
@Mixin(Bootstrap.class)
abstract class BootstrapCacheMixin {
	@Inject(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/FireBlock;bootStrap()V", shift = At.Shift.AFTER))
	private static void cadence$caches(CallbackInfo ci) {
		Startup.finishBlockCaches();
	}
}
