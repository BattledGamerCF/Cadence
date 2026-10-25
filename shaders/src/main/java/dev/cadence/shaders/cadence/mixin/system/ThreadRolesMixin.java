package dev.cadence.shaders.cadence.mixin.system;

import dev.cadence.shaders.cadence.system.ThreadRoles;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (ThreadRoles): the integrated server's thread is marked user-initiated when it starts. */
@Mixin(MinecraftServer.class)
public abstract class ThreadRolesMixin {
	@Inject(method = "runServer", at = @At("HEAD"))
	private void cadence$serverRole(CallbackInfo ci) {
		ThreadRoles.serverThread();
	}
}
