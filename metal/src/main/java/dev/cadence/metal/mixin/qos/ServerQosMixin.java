// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.qos;

import dev.cadence.metal.system.Qos;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dcadence.qos.server: the integrated server thread's class, set from the thread as it starts. */
@Mixin(MinecraftServer.class)
abstract class ServerQosMixin {
	@Inject(method = "runServer", at = @At("HEAD"))
	private void cadence$qos(CallbackInfo ci) {
		Qos.self("server");
	}
}
