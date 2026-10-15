// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
    targets = "net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.FlatLighter",
    remap = false)
public abstract class TerrainIndigoFlatLightingMixin {
  @Inject(method = "applyDirectionalBrightness", at = @At("HEAD"), cancellable = true)
  private void shaders$removeDirectionalBrightness(CallbackInfo callback) {
    if (TerrainShaderGeometry.hasSection()) callback.cancel();
  }
}
