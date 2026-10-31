package dev.cadence.metal.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cadence HDR: the main target's own first allocation (its constructor's path), marked as HdrFrameTargetMixin marks a resize. */
@Mixin(MainTarget.class)
abstract class HdrMainTargetMixin {
	@Inject(method = "allocateColorAttachment", at = @At("HEAD"))
	private void cadence$hdrBegin(CallbackInfoReturnable<?> cir) {
		dev.cadence.metal.backend.Hdr.creatingFrame = true;
	}

	@Inject(method = "allocateColorAttachment", at = @At("RETURN"))
	private void cadence$hdrEnd(CallbackInfoReturnable<?> cir) {
		dev.cadence.metal.backend.Hdr.creatingFrame = false;
	}
}
