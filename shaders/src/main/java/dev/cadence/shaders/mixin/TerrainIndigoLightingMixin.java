// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Retain Indigo's per-vertex AO calculation and put its result in the pack's alpha channel. */
@Pseudo
@Mixin(
    targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl",
    remap = false)
public abstract class TerrainIndigoLightingMixin {
  @WrapOperation(
      method = "shadeQuad",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;scaleRGB(IF)I"))
  private int shaders$separateIndigoAmbientOcclusion(
      int color, float ambientOcclusion, Operation<Integer> original) {
    if (!TerrainShaderGeometry.separateAo()) return original.call(color, ambientOcclusion);
    int alpha = Math.clamp(Math.round((color >>> 24) * ambientOcclusion), 0, 255);
    return (color & 0x00ffffff) | (alpha << 24);
  }
}
