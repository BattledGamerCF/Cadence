package dev.cadence.shaders.cadence.mixin.render;

import dev.cadence.metal.framegen.FrameGen;
import dev.cadence.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (Frame Generation, dev.cadence.metal.framegen.FrameGen): the world's snapshot for the generated frames. The game's own
 * frame: right before the hand is drawn (the hand and the GUI then come from the real frame as they are). A shader pack's
 * frame: once its final pass has run (its hand is in it; its depth says where, FrameGen keeps the nearest pixels in place).
 * With World Resolution below 100% (the world's depth is in the scaled target) no snapshot: FrameGen stands down after a few
 * frames without one.
 */
@Mixin(GameRenderer.class)
abstract class FrameGenSnapshotMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void cadence$fgWorld(CallbackInfo ci) {
		// (World Resolution below 100%: the world is in the scaled target, whose depth the upscale has already dealt with by here;
		// WorldScale.active() is already false at this point, so the scale itself decides, BUG-HUNT BH4-02)
		if (!FrameGen.running() || ShaderRuntime.get() != null || dev.cadence.shaders.cadence.render.RenderScale.get() < 0.995f) return;
		var target = ((GameRenderer) (Object) this).mainRenderTarget();
		FrameGen.snapshot(target.getColorTexture(), target.getDepthTexture(), false, 0);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V", shift = At.Shift.AFTER))
	private void cadence$fgPack(CallbackInfo ci) {
		ShaderRuntime runtime = ShaderRuntime.get();
		if (!FrameGen.running() || runtime == null || runtime.targets() == null) return;
		var target = ((GameRenderer) (Object) this).mainRenderTarget();
		FrameGen.snapshot(target.getColorTexture(), runtime.targets().depth(0).texture, true, 0.6f);
	}
}
