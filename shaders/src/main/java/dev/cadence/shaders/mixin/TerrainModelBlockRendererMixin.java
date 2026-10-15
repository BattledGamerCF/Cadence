// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ModelBlockRenderer.class)
public abstract class TerrainModelBlockRendererMixin {
  @WrapMethod(method = "tesselateBlock")
  private void shaders$blockContext(
      BlockQuadOutput output,
      float x,
      float y,
      float z,
      BlockAndTintGetter level,
      BlockPos pos,
      BlockState state,
      BlockStateModel model,
      long seed,
      Operation<Void> original) {
    if (!TerrainShaderGeometry.hasSection()) {
      original.call(output, x, y, z, level, pos, state, model, seed);
      return;
    }
    TerrainShaderGeometry.beginBlock(state, pos, false);
    try {
      original.call(output, x, y, z, level, pos, state, model, seed);
    } finally {
      TerrainShaderGeometry.endBlock();
    }
  }
}
