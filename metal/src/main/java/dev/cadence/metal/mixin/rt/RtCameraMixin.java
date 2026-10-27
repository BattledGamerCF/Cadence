package dev.cadence.metal.mixin.rt;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.cadence.metal.rt.RayTracing;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ray Tracing: the level frame's camera (its position and rotation), to rebuild each pixel's point from the depth. */
@Mixin(LevelRenderer.class)
abstract class RtCameraMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void cadence$rtCamera(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog,
		Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
		if (RayTracing.mode() != RayTracing.Mode.OFF) RayTracing.camera(cameraState.pos.x, cameraState.pos.y, cameraState.pos.z, cameraState.viewRotationMatrix);
	}
}
