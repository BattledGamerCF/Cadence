// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.cadence.shaders.runtime.ShaderRenderPass;
import dev.cadence.shaders.runtime.ShaderRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FrontendCommandEncoder.class)
public abstract class ShaderCommandEncoderMixin {
  @Inject(
      method =
          "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
      at = @At("HEAD"),
      cancellable = true)
  private void shaders$scenePass(
      RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPass> cir) {
    ShaderRuntime runtime = ShaderRuntime.get();
    if (runtime != null && runtime.intercept(descriptor))
      cir.setReturnValue(new ShaderRenderPass(runtime));
  }
}
