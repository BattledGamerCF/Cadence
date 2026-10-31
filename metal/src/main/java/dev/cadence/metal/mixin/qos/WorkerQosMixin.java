// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.qos;

import dev.cadence.metal.system.Qos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dcadence.qos.worker: Minecraft's Worker-Main pool threads (Util.makeExecutor's ForkJoinWorkerThread subclass). */
@Mixin(targets = "net.minecraft.util.Util$2")
abstract class WorkerQosMixin {
	@Inject(method = "onStart", at = @At("HEAD"))
	private void cadence$qos(CallbackInfo ci) {
		Qos.self("worker");
	}
}
