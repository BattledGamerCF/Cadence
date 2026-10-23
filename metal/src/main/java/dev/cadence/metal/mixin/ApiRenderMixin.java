package dev.cadence.metal.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cadence.api.RenderStage;
import dev.cadence.metal.ApiHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence API (dev.cadence.api.Cadence#onRender): the level's stages. Each costs one check per frame while no addon hooks it. */
@Mixin(LevelRenderer.class)
abstract class ApiRenderMixin {
	/** (the camera of the frame being drawn: render's argument, for the stages inside it) */
	@Unique private static CameraRenderState cadence$camera;

	@Inject(method = "render", at = @At("HEAD"))
	private void cadence$apiCamera(com.mojang.blaze3d.resource.GraphicsResourceAllocator resources, boolean renderOutline, CameraRenderState camera,
		com.mojang.renderpearl.api.buffers.GpuBufferSlice terrainFog, org.joml.Vector4f fogColor, boolean sky, boolean consistentDepth, CallbackInfo ci) {
		cadence$camera = camera;
	}

	@Inject(method = "executeSolid", at = @At("RETURN"))
	private void cadence$apiOpaque(net.minecraft.client.renderer.chunk.ChunkSectionsToRender chunks,
		net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame features, RenderPass pass, CallbackInfo ci) {
		if (ApiHooks.wanted(RenderStage.AFTER_OPAQUE)) cadence$fire(RenderStage.AFTER_OPAQUE, pass);
	}

	@Inject(method = "executeClassicTransparency", at = @At("RETURN"))
	private void cadence$apiTranslucent(net.minecraft.client.renderer.chunk.ChunkSectionsToRender chunks,
		net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame features, RenderPass pass, CallbackInfo ci) {
		if (ApiHooks.wanted(RenderStage.AFTER_TRANSLUCENT)) cadence$fire(RenderStage.AFTER_TRANSLUCENT, pass);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void cadence$apiLevel(com.mojang.blaze3d.resource.GraphicsResourceAllocator resources, boolean renderOutline, CameraRenderState camera,
		com.mojang.renderpearl.api.buffers.GpuBufferSlice terrainFog, org.joml.Vector4f fogColor, boolean sky, boolean consistentDepth, CallbackInfo ci) {
		if (ApiHooks.wanted(RenderStage.AFTER_LEVEL)) cadence$fire(RenderStage.AFTER_LEVEL, null);
	}

	@Unique
	private static void cadence$fire(RenderStage stage, RenderPass pass) {
		CameraRenderState c = cadence$camera;
		if (c == null) return;
		ApiHooks.fire(stage, pass, c.pos, c.viewRotationMatrix, c.projectionMatrix, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
	}
}
