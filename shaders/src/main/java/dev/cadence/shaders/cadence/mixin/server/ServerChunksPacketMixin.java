package dev.cadence.shaders.cadence.mixin.server;

import dev.cadence.shaders.cadence.server.ServerChunks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (ServerChunks, Keep Server Chunks): a chunk the server forgets kept while within the player's render distance. */
@Mixin(ClientPacketListener.class)
abstract class ServerChunksPacketMixin {
	// (after the thread check: the packet arrives on the network thread first and is handed to the render thread there)
	@Inject(method = "handleForgetLevelChunk", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V"))
	private void cadence$keep(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
		if (ServerChunks.keep(packet.pos())) ci.cancel();
	}
}
