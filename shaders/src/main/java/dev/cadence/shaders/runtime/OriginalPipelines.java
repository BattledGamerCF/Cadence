// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Render-thread-only association; compiled pipelines do not otherwise retain their descriptions.
 */
public final class OriginalPipelines {
  private static final Map<CompiledRenderPipeline, RenderPipeline> ORIGINALS =
      new IdentityHashMap<>();

  public static void observe(CompiledRenderPipeline compiled, RenderPipeline original) {
    if (compiled != null) ORIGINALS.put(compiled, original);
  }

  public static RenderPipeline get(CompiledRenderPipeline compiled) {
    RenderPipeline result = ORIGINALS.get(compiled);
    if (result == null) throw new IllegalStateException("Unidentified scene pipeline: " + compiled);
    return result;
  }

  public static void prune() {
    ORIGINALS.keySet().removeIf(CompiledRenderPipeline::isClosed);
  }

  public static void clear() {
    ORIGINALS.clear();
  }
}
