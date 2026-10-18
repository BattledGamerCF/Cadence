package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.system.CadenceMessages;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (CadenceMessages): the join line, and /cadence handled on the client (typed or clicked), never sent on. */
@Mixin(ClientPacketListener.class)
public abstract class CadenceMessagesMixin {
	@Inject(method = "handleLogin", at = @At("RETURN"))
	private void cadence$joined(ClientboundLoginPacket packet, CallbackInfo ci) {
		CadenceMessages.joined(((ClientPacketListener) (Object) this).getConnection());
	}

	@Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
	private void cadence$typed(String command, CallbackInfo ci) {
		if (CadenceMessages.command(command)) ci.cancel();
	}

	@Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
	private void cadence$clicked(String command, Screen screen, CallbackInfo ci) {
		if (CadenceMessages.command(command)) ci.cancel();
	}
}
