// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.lod;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Ground truth (test only, -Dcadence.lod.truthRd=N): the integrated server sends chunks out to N instead of 32. */
@Mixin(ChunkMap.class)
abstract class LodTruthChunkMapMixin {
	@ModifyConstant(method = "setServerViewDistance", constant = @Constant(intValue = 32), require = 1)
	private int cadence$truthView(int max) {
		return Math.max(max, Integer.getInteger("cadence.lod.truthRd", max));
	}
}
