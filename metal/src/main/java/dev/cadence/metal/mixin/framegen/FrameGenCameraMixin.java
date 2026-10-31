package dev.cadence.metal.mixin.framegen;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.cadence.metal.framegen.FrameGen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frame Generation: the level frame's camera (its position and rotation), for the reprojection. */
@Mixin(LevelRenderer.class)
abstract class FrameGenCameraMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void cadence$fgCamera(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog,
		Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
		FrameGen.camera(cameraState.pos.x, cameraState.pos.y, cameraState.pos.z, cameraState.viewRotationMatrix);
	}
}
