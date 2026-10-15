// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(
    targets = "net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.AoCalculator",
    remap = false)
public abstract class TerrainIndigoAoMixin {
  @ModifyExpressionValue(
      method =
          "computeFace(Lnet/fabricmc/fabric/impl/client/indigo/renderer/aocalc/AoFaceData;Lnet/minecraft/core/Direction;ZLnet/minecraft/core/Direction;)V",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/CardinalLighting;byFace(Lnet/minecraft/core/Direction;)F"))
  private float shaders$removeDirectionalShade(float shade) {
    return TerrainShaderGeometry.hasSection() ? 1.0f : shade;
  }

  @WrapOperation(
      method =
          "calcVanilla(Lnet/fabricmc/fabric/impl/client/indigo/renderer/mesh/QuadViewImpl;[F[I)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;redFloat(I)F"))
  private float shaders$readVanillaAmbientOcclusion(int color, Operation<Float> original) {
    // Our vanilla lighter already separated AO. Indigo's VANILLA mode must read that channel.
    return TerrainShaderGeometry.separateAo() ? (color >>> 24) / 255.0f : original.call(color);
  }
}
