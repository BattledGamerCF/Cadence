package dev.cadence.shaders.cadence.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.cadence.metal.framegen.FrameGen;
import dev.cadence.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (Frame Generation: Ultra, dev.cadence.metal.framegen.FrameGen): at each frame's start, whether its world is drawn or
 * generated; a generated frame skips the game's world drawing and gets the reprojected snapshot in its place, then the hand and
 * the GUI are drawn as usual. With a shader pack the pack's whole frame is skipped (ShaderGameRendererMixin), its hand kept in
 * the snapshot.
 */
@Mixin(GameRenderer.class)
abstract class FrameGenUltraMixin {
	@Shadow
	private void bobHurt(CameraRenderState cameraState, PoseStack poseStack) {
	}

	@Shadow
	private void bobView(CameraRenderState cameraState, PoseStack poseStack) {
	}

	@Unique private static final Matrix4f CADENCE_PROJ = new Matrix4f();

	@Inject(method = "render", at = @At("HEAD"))
	private void cadence$ultraBegin(CallbackInfo ci) {
		if (FrameGen.effective() != FrameGen.Mode.ULTRA) {
			FrameGen.ultraBegin(0, 0, 0, null, null);  // (keeps its per-second counts and "not generating")
			return;
		}
		var state = ((GameRenderer) (Object) this).gameRenderState();
		CameraRenderState cam = state.levelRenderState.cameraRenderState;
		if (cam == null || !cam.initialized || cam.pos == null || cam.viewRotationMatrix == null || cam.projectionMatrix == null) {
			FrameGen.ultraBegin(0, 0, 0, null, null);
			return;
		}
		// the projection as renderLevel makes it: the camera's, with the hurt tilt and view bobbing (as the snapshots have it)
		PoseStack pose = new PoseStack();
		bobHurt(cam, pose);
		if (state.optionsRenderState.bobView) bobView(cam, pose);
		CADENCE_PROJ.set(cam.projectionMatrix).mul(pose.last().pose());
		FrameGen.ultraBegin(cam.pos.x, cam.pos.y, cam.pos.z, cam.viewRotationMatrix, CADENCE_PROJ);
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"))
	private void cadence$ultraWorld(LevelRenderer levelRenderer, GraphicsResourceAllocator allocator, boolean outline, CameraRenderState cam,
		GpuBufferSlice fog, Vector4f fogColor, boolean sky, boolean consistentDepth, Operation<Void> original) {
		if (FrameGen.generating() && ShaderRuntime.get() == null
			&& FrameGen.ultraDraw(((GameRenderer) (Object) this).mainRenderTarget().getColorTexture())) {
			dev.cadence.metal.lod.Lod.idle();  // (far terrain's finished work still applied now and then, as with Render Max FPS)
			return;
		}
		original.call(levelRenderer, allocator, outline, cam, fog, fogColor, sky, consistentDepth);
	}
}
