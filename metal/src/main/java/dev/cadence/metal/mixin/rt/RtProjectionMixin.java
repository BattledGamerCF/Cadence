package dev.cadence.metal.mixin.rt;

import dev.cadence.metal.rt.RayTracing;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Ray Tracing: the projection the world is drawn with (view bobbing and nausea folded in, as the GPU gets it). */
@Mixin(GameRenderer.class)
abstract class RtProjectionMixin {
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f cadence$rtProjection(Matrix4f projection) {
		if (RayTracing.mode() != RayTracing.Mode.OFF) RayTracing.projection(projection);
		return projection;
	}
}
