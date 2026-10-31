package dev.cadence.shaders.cadence.mixin.render;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Cadence (Video Settings > Far Terrain > Far Clouds): the clouds reach out with the far terrain (to its reach, at most 256
 * chunks) instead of stopping at the Cloud Distance option, so the far terrain's sky isn't bare. Off: the game's own distance.
 */
@Mixin(CloudRenderer.class)
abstract class FarCloudsMixin {
	@ModifyVariable(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private int cadence$farClouds(int cloudDistance) {
		int far = dev.cadence.shaders.cadence.ui.FarTerrainScreen.farCloudChunks();
		return far > cloudDistance ? far : cloudDistance;
	}
}
