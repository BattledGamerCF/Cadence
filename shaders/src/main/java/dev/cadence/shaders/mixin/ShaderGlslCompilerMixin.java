// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import dev.cadence.shaders.compile.ShadercFragmentOptimization;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import dev.cadence.shaders.compile.SpirvCache;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Changes only selected stages of our pack shaders before normal reflection/linkage. */
@Mixin(GlslCompiler.class)
public abstract class ShaderGlslCompilerMixin {
  @ModifyExpressionValue(
      method = "compileToSpv",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/renderpearl/frontend/shaders/GlslCompiler;createBaseShaderOptions()J"))
  private long shaders$fragmentOptimization(
      long options,
      @Local(argsOnly = true, ordinal = 0) String name,
      @Local(argsOnly = true) ShaderType stage) {
    return ShadercFragmentOptimization.configure(options, name, stage);
  }

  /** Cadence: a shader pack program's SPIR-V from the disk cache when this exact compile was done before (SpirvCache). */
  @Inject(method = "compileToSpv", at = @At("HEAD"), cancellable = true)
  private void shaders$cachedSpv(
      String name, String source, ShaderType type, ShaderDefines defines, ShaderSource includes,
      CallbackInfoReturnable<SpvModule> cir) {
    if (!SpirvCache.applies(name)) return;
    var cached = SpirvCache.read(SpirvCache.key(name, source, type, defines.asSourceDirectives()));
    if (cached != null) cir.setReturnValue(new SPIRVModule(cached, type));
  }

  @Inject(method = "compileToSpv", at = @At("RETURN"))
  private void shaders$storeSpv(
      String name, String source, ShaderType type, ShaderDefines defines, ShaderSource includes,
      CallbackInfoReturnable<SpvModule> cir) {
    if (!SpirvCache.applies(name) || cir.getReturnValue() == null) return;
    SpirvCache.write(SpirvCache.key(name, source, type, defines.asSourceDirectives()), cir.getReturnValue().spv());
  }
}
