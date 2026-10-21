package dev.cadence.shaders.cadence.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cadence.shaders.cadence.render.WorldScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Cadence (WorldScale, CrispText): the scaled world is finished when the level's features are done, before their frame data
 * is closed: upscaled, then the world's text drawn at full resolution while that data is still there. The hand comes after,
 * at full resolution.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererWorldScaleMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;close()V"))
	private void cadence$finishScaledWorld(FeatureRenderDispatcher.PreparedFrame frame, Operation<Void> original) {
		WorldScale.finishWorld(Minecraft.getInstance().gameRenderer);
		original.call(frame);
	}
}
