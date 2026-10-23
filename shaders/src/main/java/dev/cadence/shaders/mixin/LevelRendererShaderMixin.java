// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cadence.shaders.runtime.ShaderRuntime;
import dev.cadence.shaders.runtime.ShadowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Places true shadow and deferred passes at the boundaries of Minecraft's scene graph. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererShaderMixin {
  @Shadow private ViewArea viewArea;

  @Inject(
      method = {"close", "resetLevelRenderData"},
      at = @At("HEAD"))
  private void shaders$releaseExtraction(CallbackInfo callback) {
    ShadowRenderer.beginExtraction();
  }

  @Inject(method = "addMainPass", at = @At("HEAD"))
  private void shaders$renderShadows(
      FrameGraphBuilder frame,
      FeatureRenderDispatcher.PreparedFrame features,
      GpuBufferSlice terrainFog,
      ChunkSectionsToRender sceneChunks,
      boolean consistentDepthRequired,
      CallbackInfo callback) {
    if (ShaderRuntime.isRendering()) {
      ShadowRenderer.render((LevelRenderer) (Object) this, viewArea, features, terrainFog);
    }
  }

  @Inject(
      method = "render",
      at = @At(value = "INVOKE",
          target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
  private void shaders$prefetchShadowSelection(CallbackInfo callback) {
    ShadowRenderer.prefetchSelection((LevelRenderer) (Object) this, viewArea);
  }

  // Cadence: the far terrain through the pack's dh_terrain program, before the real terrain (DistantTerrain)
  @Inject(method = "executeSolid", at = @At("HEAD"))
  private void shaders$distantTerrain(CallbackInfo callback) {
    if (ShaderRuntime.isRendering()) ShaderRuntime.get().drawDistantTerrain();
  }

  @Inject(method = "executeClassicTransparency", at = @At("HEAD"))
  private void shaders$deferredBeforeTranslucents(
      ChunkSectionsToRender chunks,
      FeatureRenderDispatcher.PreparedFrame features,
      RenderPass pass,
      CallbackInfo callback) {
    if (ShaderRuntime.isRendering()) ShaderRuntime.get().beforeTranslucents();
  }
}
