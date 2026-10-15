// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.cadence.shaders.runtime.OriginalPipelines;
import dev.cadence.shaders.runtime.ShaderRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSystem.class)
public abstract class ShaderRenderSystemMixin {
  @Inject(method = "getCompiledPipelineNullable", at = @At("RETURN"))
  private static void shaders$rememberDescription(
      RenderPipeline original, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
    if (ShaderRuntime.get() != null) OriginalPipelines.observe(cir.getReturnValue(), original);
  }
}
