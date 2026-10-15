// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.own.OwnSolid;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Solid-section occlusion: every section vanilla marks dirty is checked again (OwnSolid; nothing without -Dcadence.own.solidOcc). */
@Mixin(LevelExtractor.class)
abstract class OwnSolidExtractorMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void cadence$solidDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
		OwnSolid.dirty(sectionX, sectionY, sectionZ);
	}

	/**
	 * Leaving the level (setLevel(null)): vanilla only flags its reset for the next level's first frame, so our sections (and their
	 * arena pages) stayed resident on the title screen. They go now: every section released, the arena renewed (OwnTerrain.disposeAll;
	 * the reset at the next level then has nothing left to do).
	 */
	@Inject(method = "setLevel", at = @At("RETURN"))
	private void cadence$ownLevelGone(net.minecraft.client.multiplayer.ClientLevel level, CallbackInfo ci) {
		if (level != null) return;
		dev.cadence.metal.own.OwnTerrain own = dev.cadence.metal.own.OwnTerrain.get();
		if (own != null) own.disposeAll();
	}
}
