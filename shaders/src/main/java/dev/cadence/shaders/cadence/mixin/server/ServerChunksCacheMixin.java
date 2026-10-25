package dev.cadence.shaders.cadence.mixin.server;

import dev.cadence.shaders.cadence.server.ServerChunks;
import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (ServerChunks, Keep Server Chunks): the client's chunk storage as big as the player's render distance on a server (a new
 * level's storage starts at the server's radius; ServerChunks.check raises it within a second, before chunks past it can matter).
 */
@Mixin(ClientChunkCache.class)
abstract class ServerChunksCacheMixin {
	@ModifyVariable(method = "updateViewRadius", at = @At("HEAD"), argsOnly = true)
	private int cadence$storage(int radius) {
		return ServerChunks.storageRadius(radius);
	}

	@Inject(method = "updateViewCenter", at = @At("HEAD"))
	private void cadence$centreMoving(int x, int z, CallbackInfo ci) {
		ServerChunks.centreMoving(x, z);
	}

	@Inject(method = "replaceWithPacketData", at = @At("HEAD"))
	private void cadence$sent(int x, int z, net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData data,
		org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.level.chunk.LevelChunk> cir) {
		ServerChunks.sent(x, z);
	}
}
