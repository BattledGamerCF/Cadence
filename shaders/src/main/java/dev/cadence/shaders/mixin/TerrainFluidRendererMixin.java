// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FluidRenderer.class)
public abstract class TerrainFluidRendererMixin {
  @Unique
  private static final CardinalLighting shaders$unshaded = new CardinalLighting(1, 1, 1, 1, 1, 1);

  @ModifyExpressionValue(
      method = "tesselate",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;cardinalLighting()Lnet/minecraft/world/level/CardinalLighting;"))
  private CardinalLighting shaders$unlitFluidTint(CardinalLighting original) {
    return TerrainShaderGeometry.hasSection() ? shaders$unshaded : original;
  }

  @WrapMethod(method = "tesselate")
  private void shaders$fluidContext(
      BlockAndTintGetter level,
      BlockPos pos,
      FluidRenderer.Output output,
      BlockState blockState,
      FluidState fluidState,
      Operation<Void> original) {
    if (!TerrainShaderGeometry.hasSection()) {
      original.call(level, pos, output, blockState, fluidState);
      return;
    }
    // Waterlogged blocks have distinct solid and fluid surfaces; use the fluid's own material ID.
    TerrainShaderGeometry.beginBlock(fluidState.createLegacyBlock(), pos, true);
    try {
      original.call(level, pos, output, blockState, fluidState);
    } finally {
      // The section loop emits a waterlogged block's solid model immediately after its fluid.
      TerrainShaderGeometry.beginBlock(blockState, pos, false);
    }
  }
}
