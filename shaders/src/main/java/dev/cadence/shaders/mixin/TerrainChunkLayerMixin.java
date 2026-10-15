// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps meshing, arena alignment, shader layouts, and indirect offsets on the same vertex stride.
 */
@Mixin(ChunkSectionLayer.class)
public abstract class TerrainChunkLayerMixin {
  @Inject(method = "pipeline", at = @At("RETURN"), cancellable = true)
  private void shaders$terrainPipeline(
      boolean multiDraw, CallbackInfoReturnable<RenderPipeline> callback) {
    if (TerrainShaderGeometry.isEnabledForThread()) {
      callback.setReturnValue(TerrainShaderGeometry.pipeline(callback.getReturnValue()));
    }
  }

  @Inject(method = "vertexFormat", at = @At("HEAD"), cancellable = true)
  private void shaders$terrainFormat(CallbackInfoReturnable<VertexFormat> callback) {
    if (TerrainShaderGeometry.isEnabledForThread())
      callback.setReturnValue(TerrainShaderGeometry.layout().format());
  }
}
