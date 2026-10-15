// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import java.util.concurrent.Executor;

/** Chooses execution only for a cache miss whose caller immediately joins its compilation. */
public final class BlockingPipelineCompilation {
  private static final Executor CALLER = Runnable::run;

  private BlockingPipelineCompilation() {}

  public static Executor executor(Executor requested, boolean packSelected, boolean renderThread) {
    // Chunk workers can fill the upload queue and wait for the render thread to drain it.
    // Enqueuing a compile behind those workers and joining from that same render thread creates
    // a cycle. This blocking lookup gains no parallelism from a worker hop; compile it here.
    return packSelected && renderThread ? CALLER : requested;
  }
}
