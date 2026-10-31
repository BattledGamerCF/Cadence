package dev.cadence.shaders.cadence.mixin.hiz;

import dev.cadence.shaders.cadence.hiz.HiZ;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Cadence (HiZ, no shader pack): the level's projection exactly as uploaded for drawing — the camera's projection with
 * view bobbing, hurt tilt and nausea multiplied in — so the occlusion test projects sections the way they're drawn.
 */
@Mixin(GameRenderer.class)
public abstract class HiZProjectionMixin {
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f cadence$levelProjection(Matrix4f projection) {
		HiZ.levelProjection(projection);
		return projection;
	}
}
