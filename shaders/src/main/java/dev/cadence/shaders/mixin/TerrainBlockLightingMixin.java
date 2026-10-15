// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.QuadInstance;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep texture tint separate from lighting, which the selected pack calculates itself. */
@Mixin(BlockModelLighter.class)
public abstract class TerrainBlockLightingMixin {
  @WrapOperation(
      method = "prepareQuadAmbientOcclusion",
      at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/QuadInstance;scaleColor(F)V"))
  private void shaders$separateAmbientOcclusion(
      QuadInstance instance, float directionalShade, Operation<Void> original) {
    if (!TerrainShaderGeometry.hasSection()) {
      original.call(instance, directionalShade);
      return;
    }
    if (TerrainShaderGeometry.separateAo()) {
      for (int vertex = 0; vertex < 4; vertex++) {
        int ao = instance.getColor(vertex) & 255;
        instance.setColor(vertex, (ao << 24) | 0x00ffffff);
      }
    }
    // Ambient occlusion stays in RGB when separateAo is false. The pack supplies directional light.
  }

  @Inject(method = "prepareQuadFlat", at = @At("RETURN"))
  private void shaders$removeBakedDirectionalLight(
      BlockAndTintGetter level,
      BlockState state,
      BlockPos pos,
      int lightCoords,
      BakedQuad quad,
      QuadInstance outputInstance,
      CallbackInfo callback) {
    if (TerrainShaderGeometry.hasSection()) outputInstance.setColor(0xffffffff);
  }
}
