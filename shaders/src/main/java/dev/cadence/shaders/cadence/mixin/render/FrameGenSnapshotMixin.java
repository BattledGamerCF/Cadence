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
		// (Ray Tracing's shadows go into the world first, so the snapshot has them; called when off too, which lets its world go,
		// BH7-03)
		var main = ((GameRenderer) (Object) this).mainRenderTarget();
		boolean pack = ShaderRuntime.get() != null, scaled = dev.cadence.shaders.cadence.render.RenderScale.get() < 0.995f;
		dev.cadence.metal.rt.RayTracing.afterWorld(main.getColorTexture(), main.getDepthTexture(), pack, scaled);
		// (Biome Colours: the grade first, so a style filter and the generated frames see it; a pack grades its own picture)
		dev.cadence.metal.grade.BiomeGrade.afterWorld(pack ? null : main.getColorTexture());
		// (Style Filters: the newest styled picture over the world, before the snapshot too; off, with a pack or scaled, its network
		// is let go, BH7-05)
		dev.cadence.metal.neural.StyleFilter.afterWorld(pack || scaled ? null : main.getColorTexture());
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
