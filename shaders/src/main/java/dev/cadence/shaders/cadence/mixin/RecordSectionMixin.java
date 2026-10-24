package dev.cadence.shaders.cadence.mixin;

import dev.cadence.metal.record.Record;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (Record Mode): a section the game rebuilds (a block changed) is copied again into Record Mode's voxel window. */
@Mixin(LevelExtractor.class)
abstract class RecordSectionMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void cadence$recordDirty(int x, int y, int z, boolean important, CallbackInfo ci) {
		if (Record.chosen()) Record.sectionDirty(x, y, z);
	}
}
