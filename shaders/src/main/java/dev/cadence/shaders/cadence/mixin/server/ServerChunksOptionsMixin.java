package dev.cadence.shaders.cadence.mixin.server;

import dev.cadence.shaders.cadence.server.ServerChunks;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Cadence (ServerChunks, Keep Server Chunks): no server cap on the render distance while chunks are kept. */
@Mixin(Options.class)
abstract class ServerChunksOptionsMixin {
	@ModifyVariable(method = "setServerRenderDistance", at = @At("HEAD"), argsOnly = true)
	private int cadence$noCap(int distance) {
		return ServerChunks.serverRenderDistance(distance);
	}
}
