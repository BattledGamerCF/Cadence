// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.own.OwnSeam;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * -Dcadence.own.int.seam with -Dcadence.lod.fade=instant: the sections vanilla still draws itself (translucent terrain, unless
 * -Dcadence.own.translucent) appear without the fog fade-in while far terrain draws, as ours do (OwnSeam.fadeMs).
 */
@Mixin(LevelRenderer.class)
abstract class OwnIntLevelRendererMixin {
	@ModifyVariable(method = "extractSectionDrawGroups", at = @At("STORE"), ordinal = 0)
	private long cadence$ownFade(long fadeDuration) {
		return OwnSeam.fadeMs(fadeDuration);
	}
}
