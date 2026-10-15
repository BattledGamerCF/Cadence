// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.cadence.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Respect underwaterOverlay without suppressing independent fire, block, or item effects. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ShaderScreenEffectMixin {
  @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
  private static void shaders$packWaterOverlay(
      PlayerRenderState.WaterOverlay overlay,
      PoseStack poses,
      SubmitNodeCollector collector,
      CallbackInfo callback) {
    ShaderRuntime runtime = ShaderRuntime.get();
    if (ShaderRuntime.isRendering()
        && runtime.properties().get("underwaterOverlay", "true").equals("false")) callback.cancel();
  }
}
