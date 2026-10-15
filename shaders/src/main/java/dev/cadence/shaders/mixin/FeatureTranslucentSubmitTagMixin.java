// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import dev.cadence.shaders.geometry.FeatureDrawContext;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TranslucentFeatureRenderPhase.class)
public abstract class FeatureTranslucentSubmitTagMixin {
  @Inject(
      method = "submit(Lnet/minecraft/client/renderer/feature/submit/TranslucentSubmit;)V",
      at = @At("HEAD"))
  private void shaders$tagSubmit(TranslucentSubmit submit, CallbackInfo callback) {
    FeatureDrawContext.recordSubmit(submit);
  }
}
