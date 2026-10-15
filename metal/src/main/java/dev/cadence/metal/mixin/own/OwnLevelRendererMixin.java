// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cadence.metal.own.OwnSeam;
import dev.cadence.metal.own.OwnTerrain;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Util;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Our near terrain draws the opaque group (solid, cutout) in vanilla's place; translucent stays vanilla's. */
@Mixin(LevelRenderer.class)
abstract class OwnLevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;
	@Shadow
	@Final
	private OptionsRenderState optionsRenderState;
	@Shadow
	private @Nullable ViewArea viewArea;

	@Inject(method = "render", at = @At("HEAD"))
	private void cadence$ownInit(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog,
		Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
		OwnTerrain.init();
	}

	@Redirect(method = "executeSolid", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
	private void cadence$ownOpaque(ChunkSectionsToRender sections, ChunkSectionLayerGroup group, RenderPass renderPass, GpuSampler sampler, GpuTextureView atlas,
		boolean wireframe) {
		OwnTerrain own = OwnTerrain.get();
		if (own != null && group == ChunkSectionLayerGroup.OPAQUE && !wireframe && this.viewArea != null
			&& own.drawOpaque(this.levelRenderState.cameraRenderState, this.viewArea.getViewDistance(), OwnSeam.fadeMs(Util.toMillis(this.optionsRenderState.chunkSectionFadeInTime)),
				sampler, atlas, ((LevelRenderer) (Object) this).visibleSections())) {
			return;
		}
		sections.renderGroup(group, renderPass, sampler, atlas, wireframe);
	}

	@Redirect(method = "executeClassicTransparency", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
	private void cadence$ownTranslucent(ChunkSectionsToRender sections, ChunkSectionLayerGroup group, RenderPass renderPass, GpuSampler sampler, GpuTextureView atlas,
		boolean wireframe) {
		OwnTerrain own = OwnTerrain.get();
		if (own != null && OwnTerrain.TRANSLUCENT && group == ChunkSectionLayerGroup.TRANSLUCENT && !wireframe
			&& own.drawTranslucent(this.levelRenderState.cameraRenderState, OwnSeam.fadeMs(Util.toMillis(this.optionsRenderState.chunkSectionFadeInTime)), sampler, atlas,
				((LevelRenderer) (Object) this).visibleSections())) {
			return;
		}
		sections.renderGroup(group, renderPass, sampler, atlas, wireframe);
	}
}
