package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.render.HudTarget;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (HudTarget): the 3D-HUD depth target sized as each frame's hand drawing begins, from whether it draws into it. Also
 * (PanoramaTexture) the panorama texture loaded or let go as a frame begins, when no render pass is open.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHudTargetMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void cadence$panorama(CallbackInfo ci) {
		dev.cadence.shaders.cadence.render.PanoramaTexture.frame();
	}

	@Inject(method = "render3dHud", at = @At("HEAD"))
	private void cadence$hudTarget(CameraRenderState camera, PlayerRenderState player, OptionsRenderState options, boolean intoHudTarget,
		CallbackInfo ci) {
		HudTarget.frame(this, intoHudTarget);
	}
}
