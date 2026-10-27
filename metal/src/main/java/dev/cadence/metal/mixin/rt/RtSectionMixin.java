package dev.cadence.metal.mixin.rt;

import dev.cadence.metal.rt.RayTracing;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ray Tracing: a section the game rebuilds (a block or its light changed) is copied again into the voxel window. */
@Mixin(LevelExtractor.class)
abstract class RtSectionMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void cadence$rtDirty(int x, int y, int z, boolean important, CallbackInfo ci) {
		RayTracing.sectionDirty(x, y, z);
	}
}
