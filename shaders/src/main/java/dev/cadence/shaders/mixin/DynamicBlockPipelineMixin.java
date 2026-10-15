// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.cadence.shaders.geometry.DynamicBlockGeometry;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The mesher and the prepared draw must use the same extended vertex stride. */
@Mixin(RenderType.class)
public abstract class DynamicBlockPipelineMixin {
  @ModifyExpressionValue(
      method = {"prepare", "format", "pipeline"},
      at =
          @At(
              value = "FIELD",
              target =
                  "Lnet/minecraft/client/renderer/rendertype/RenderSetup;pipeline:Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"))
  private RenderPipeline shaders$dynamicBlockNormals(RenderPipeline original) {
    return DynamicBlockGeometry.pipeline(original);
  }

  @ModifyExpressionValue(
      method = "prepare",
      at =
          @At(
              value = "FIELD",
              target =
                  "Lnet/minecraft/client/renderer/rendertype/RenderSetup;oitPipelineSet:Lnet/minecraft/client/renderer/oit/OitPipelineSet;"))
  private OitPipelineSet shaders$dynamicBlockOitNormals(OitPipelineSet original) {
    return DynamicBlockGeometry.pipelines(original);
  }
}
