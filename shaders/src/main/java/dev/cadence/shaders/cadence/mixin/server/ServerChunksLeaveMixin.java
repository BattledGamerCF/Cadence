package dev.cadence.shaders.cadence.mixin.server;

import dev.cadence.shaders.cadence.server.ServerChunks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (Keep Server Chunks on disk): a world or dimension's chunks stored before the game lets go of it. */
@Mixin(Minecraft.class)
abstract class ServerChunksLeaveMixin {
	@Inject(method = "setLevel", at = @At("HEAD"))
	private void cadence$newLevel(net.minecraft.client.multiplayer.ClientLevel level, CallbackInfo ci) {
		ServerChunks.leaving();
	}

	@Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
	private void cadence$disconnect(net.minecraft.client.gui.screens.Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
		ServerChunks.leaving();
	}
}
