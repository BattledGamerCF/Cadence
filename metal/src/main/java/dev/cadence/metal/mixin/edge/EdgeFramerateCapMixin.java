// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.edge;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.cadence.metal.system.EdgeCap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Edge cap (-Dcadence.edgeCap, EdgeCap): the frame rate capped while the integrated server generates terrain near the player. */
@Mixin(FramerateLimitTracker.class)
abstract class EdgeFramerateCapMixin {
	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void cadence$edgeCap(CallbackInfoReturnable<Integer> cir) {
		int limit = cir.getReturnValueI(), capped = EdgeCap.limit(limit);
		if (capped != limit) cir.setReturnValue(capped);
	}
}
