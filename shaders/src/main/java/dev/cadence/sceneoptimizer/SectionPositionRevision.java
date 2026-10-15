// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Position events invalidate spatial snapshots even if a section moves outside vanilla recenter.
 */
public final class SectionPositionRevision {
  private static final AtomicLong REVISION = new AtomicLong();

  private SectionPositionRevision() {}

  public static void changed() {
    REVISION.incrementAndGet();
  }

  public static long current() {
    return REVISION.get();
  }
}
