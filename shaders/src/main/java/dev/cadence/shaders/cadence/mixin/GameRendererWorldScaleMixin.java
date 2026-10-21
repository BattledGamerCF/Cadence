package dev.cadence.shaders.cadence.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.cadence.shaders.cadence.render.WorldScale;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (WorldScale): the world and hand drawn into scaled targets, swapped in around renderLevel. */
@Mixin(GameRenderer.class)
public abstract class GameRendererWorldScaleMixin implements WorldScale.Targets {
	@Shadow @Final @Mutable private RenderTarget mainRenderTarget;
	@Shadow @Final @Mutable private RenderTarget hud3DTarget;

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void cadence$scaledWorld(CallbackInfo ci) {
		WorldScale.begin(this);
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void cadence$upscaleWorld(CallbackInfo ci) {
		WorldScale.end(this);
	}

	@Override
	public RenderTarget cadence$main() {
		return mainRenderTarget;
	}

	@Override
	public RenderTarget cadence$hudDepth() {
		return hud3DTarget;
	}

	@Override
	public void cadence$setTargets(RenderTarget main, RenderTarget hudDepth) {
		mainRenderTarget = main;
		hud3DTarget = hudDepth;
	}
}
