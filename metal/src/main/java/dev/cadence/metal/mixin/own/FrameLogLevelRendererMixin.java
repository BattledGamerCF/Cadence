// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.FrameLog;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (-Dcadence.own.int.frameLog): vanilla's section scheduling and translucent resort scheduling times. */
@Mixin(LevelRenderer.class)
public class FrameLogLevelRendererMixin {
	@Inject(method = "compileSections", at = @At("HEAD"))
	private void cadence$frameLogCompileHead(CameraRenderState camera, CallbackInfo ci) {
		FrameLog.begin(FrameLog.COMPILE);
	}

	@Inject(method = "compileSections", at = @At("RETURN"))
	private void cadence$frameLogCompileReturn(CameraRenderState camera, CallbackInfo ci) {
		FrameLog.end(FrameLog.COMPILE);
	}

	@Inject(method = "scheduleTranslucentSectionResort", at = @At("HEAD"))
	private void cadence$frameLogResortHead(Vec3 camera, CallbackInfo ci) {
		FrameLog.begin(FrameLog.RESORT_SCHED);
	}

	@Inject(method = "scheduleTranslucentSectionResort", at = @At("RETURN"))
	private void cadence$frameLogResortReturn(Vec3 camera, CallbackInfo ci) {
		FrameLog.end(FrameLog.RESORT_SCHED);
	}
}
