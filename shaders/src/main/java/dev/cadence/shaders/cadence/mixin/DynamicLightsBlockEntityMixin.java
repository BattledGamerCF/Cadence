package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.render.DynamicLights;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence: a block entity's light (chests, signs, banners...) with this frame's dynamic lights (DynamicLights). */
@Mixin(BlockEntityRenderState.class)
public abstract class DynamicLightsBlockEntityMixin {
	@Inject(method = "extractBase", at = @At("TAIL"))
	private static void cadence$dynamicLight(BlockEntity blockEntity, BlockEntityRenderState state, ModelFeatureRenderer.CrumblingOverlay crumbling,
		CallbackInfo callback) {
		if (DynamicLights.active() && state.blockPos != null)
			state.lightCoords = DynamicLights.raise(state.lightCoords, state.blockPos.getX() + 0.5, state.blockPos.getY() + 0.5, state.blockPos.getZ() + 0.5);
	}
}
