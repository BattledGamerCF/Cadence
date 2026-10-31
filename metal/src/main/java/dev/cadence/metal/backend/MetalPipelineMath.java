// Adapted from minecraft-metal-renderer (MIT, Copyright (c) 2026 Minecraft Metal Renderer contributors),
// https://github.com/Kausik-Velaga/minecraft-metal-renderer — see shaders/LICENSE-MIT-shader-loader.
package dev.cadence.metal.backend;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/** Caller-owned arithmetic policy for one synchronous, exactly named pipeline compilation. */
public final class MetalPipelineMath {
  private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
  private static final LongAdder requested = new LongAdder();
  private static final LongAdder relaxed = new LongAdder();
  private static final LongAdder precisionFallbacks = new LongAdder();
  private static final LongAdder platformFallbacks = new LongAdder();

  private MetalPipelineMath() {}

  /**
   * Requests Safe vertex arithmetic and Relaxed fragment arithmetic for exactly this backend
   * pipeline name. The caller must include its requested policy in its pipeline/cache identity. The
   * hint is consumed once on this thread; asynchronous work does not inherit it. Explicit SPIR-V
   * precision requirements and unsupported platforms retain Safe fragment arithmetic.
   */
  public static Scope openRelaxedFragment(String exactPipelineName) {
    Objects.requireNonNull(exactPipelineName, "exactPipelineName");
    if (exactPipelineName.isBlank()) throw new IllegalArgumentException("Empty pipeline name");
    Scope scope = new Scope(exactPipelineName, CURRENT.get());
    CURRENT.set(scope);
    return scope;
  }

  static boolean consumeRelaxedFragment(String pipelineName) {
    Scope scope = CURRENT.get();
    if (scope == null || scope.consumed || !scope.name.equals(pipelineName)) return false;
    scope.consumed = true;
    return true;
  }

  static void record(boolean precisionEligible, int actualFragmentMode) {
    requested.increment();
    if (actualFragmentMode == 1) relaxed.increment();
    else if (!precisionEligible) precisionFallbacks.increment();
    else platformFallbacks.increment();
  }

  /** Successful scoped native compilations only; no frame or draw instrumentation. */
  public static Stats snapshot() {
    return new Stats(
        requested.sum(), relaxed.sum(), precisionFallbacks.sum(), platformFallbacks.sum());
  }

  public record Stats(
      long scopedCompilations,
      long relaxedFragments,
      long precisionFallbacks,
      long platformFallbacks) {}

  public static final class Scope implements AutoCloseable {
    private final String name;
    private final Scope previous;
    private final Thread owner = Thread.currentThread();
    private boolean consumed, closed;

    private Scope(String name, Scope previous) {
      this.name = name;
      this.previous = previous;
    }

    @Override
    public void close() {
      if (closed) return;
      if (owner != Thread.currentThread() || CURRENT.get() != this)
        throw new IllegalStateException(
            "Metal math hints must close on their owner thread in nesting order");
      closed = true;
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }
}
