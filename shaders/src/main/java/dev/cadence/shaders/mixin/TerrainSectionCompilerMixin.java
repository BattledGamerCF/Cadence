// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Scope pack metadata to chunk worker jobs, including exception-safe cleanup. */
@Mixin(SectionCompiler.class)
public abstract class TerrainSectionCompilerMixin {
  @WrapOperation(
      method = "compile",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
  private BlockState shaders$meshBlockContext(
      RenderSectionRegion region, BlockPos pos, Operation<BlockState> original) {
    BlockState state = original.call(region, pos);
    // Fabric can replace the model renderer call below, but both emission paths consume this state.
    TerrainShaderGeometry.beginBlock(state, pos, false);
    return state;
  }

  @WrapMethod(method = "compile")
  private SectionCompiler.Results shaders$sectionContext(
      SectionPos sectionPos,
      RenderSectionRegion region,
      VertexSorting vertexSorting,
      SectionBufferBuilderPack builders,
      Operation<SectionCompiler.Results> original) {
    TerrainShaderGeometry.SectionContext previous = TerrainShaderGeometry.beginSection();
    try {
      return original.call(sectionPos, region, vertexSorting, builders);
    } finally {
      TerrainShaderGeometry.endSection(previous);
    }
  }
}
