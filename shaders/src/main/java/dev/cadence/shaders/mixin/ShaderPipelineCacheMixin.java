// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.cadence.shaders.runtime.BlockingPipelineCompilation;
import dev.cadence.shaders.runtime.ShaderRuntime;
import java.util.concurrent.Executor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keeps a blocking shadow/scene cache miss independent of chunk workers awaiting GPU uploads. */
@Mixin(PipelineCache.class)
public abstract class ShaderPipelineCacheMixin {
  @ModifyArg(
      method = "get",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/renderpearl/api/device/GpuDevice;compilePipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
      index = 2)
  private Executor shaders$blockingCompileExecutor(Executor requested) {
    return BlockingPipelineCompilation.executor(
        requested, ShaderRuntime.get() != null, RenderSystem.isOnRenderThread());
  }
}
