// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.backend.FrameLog;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (-Dcadence.own.int.frameLog): the frame's extract and render times. */
@Mixin(GameRenderer.class)
public class FrameLogGameRendererMixin {
	@Inject(method = "extract", at = @At("HEAD"))
	private void cadence$frameLogExtractHead(DeltaTracker deltaTracker, boolean advance, CallbackInfo ci) {
		FrameLog.begin(FrameLog.EXTRACT);
	}

	@Inject(method = "extract", at = @At("RETURN"))
	private void cadence$frameLogExtractReturn(DeltaTracker deltaTracker, boolean advance, CallbackInfo ci) {
		FrameLog.end(FrameLog.EXTRACT);
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void cadence$frameLogRenderHead(CallbackInfo ci) {
		FrameLog.begin(FrameLog.RENDER);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void cadence$frameLogRenderReturn(CallbackInfo ci) {
		FrameLog.end(FrameLog.RENDER);
	}
}
