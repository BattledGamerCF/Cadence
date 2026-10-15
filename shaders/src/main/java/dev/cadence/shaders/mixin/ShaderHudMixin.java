// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import dev.cadence.shaders.runtime.ShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A pack may supply its own vignette in the final image instead of Minecraft's HUD overlay. */
@Mixin(Hud.class)
public abstract class ShaderHudMixin {
  @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
  private void shaders$packVignette(
      GuiGraphicsExtractor graphics, Entity camera, CallbackInfo callback) {
    ShaderRuntime runtime = ShaderRuntime.get();
    // HUD extraction precedes world rendering, so the frame's active flag is deliberately unused.
    if (runtime != null
        && Minecraft.getInstance().level != null
        && runtime.properties().get("vignette", "true").equals("false")) callback.cancel();
  }
}
