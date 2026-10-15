// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import dev.cadence.scene.SceneVolume;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;

/** Immutable geometry results; opaque section values are only inspected by the consuming thread. */
final class GeometricSelection<T> {
  private static final int INSIDE = 1, PRIMARY = 2, REFINED = 4;
  private final SpatialSectionIndex<T> index;
  private final int[] ordinals;
  private final byte[] flags;
  private final int coarseTests;

  private GeometricSelection(
      SpatialSectionIndex<T> index, int[] ordinals, byte[] flags, int coarseTests) {
    this.index = index;
    this.ordinals = ordinals;
    this.flags = flags;
    this.coarseTests = coarseTests;
  }

  static <T> GeometricSelection<T> compute(
      SpatialSectionIndex<T> index, SceneVolume volume, SceneVolume refinement) {
    checkCancelled();
    var query = index.query(volume);
    int[] ordinals = new int[query.candidates().cardinality()];
    byte[] flags = new byte[ordinals.length];
    int slot = 0;
    for (int ordinal = query.candidates().nextSetBit(0);
        ordinal >= 0;
        ordinal = query.candidates().nextSetBit(ordinal + 1)) {
      if ((slot & 63) == 0) checkCancelled();
      var bounds = index.entry(ordinal).bounds();
      boolean inside = query.fullyInside().get(ordinal);
      int result = inside ? INSIDE : 0;
      if (inside || volume.intersects(bounds)) {
        result |= PRIMARY;
        if (refinement == null || refinement.intersects(bounds)) result |= REFINED;
      }
      ordinals[slot] = ordinal;
      flags[slot++] = (byte) result;
    }
    checkCancelled();
    return new GeometricSelection<>(index, ordinals, flags, query.coarseTests());
  }

  /** Preserve original order and counters, checking mesh readiness at consumption, not submission. */
  Filtered<T> filter(Predicate<T> renderable) {
    var selected = new ArrayList<T>();
    int candidates = 0, tested = 0, inside = 0;
    for (int slot = 0; slot < ordinals.length; slot++) {
      T value = index.entry(ordinals[slot]).value();
      if (!renderable.test(value)) continue;
      int result = flags[slot];
      if ((result & INSIDE) != 0) inside++;
      else tested++;
      if ((result & PRIMARY) == 0) continue;
      candidates++;
      if ((result & REFINED) != 0) selected.add(value);
    }
    return new Filtered<>(List.copyOf(selected), candidates, tested, inside, coarseTests);
  }

  private static void checkCancelled() {
    if (Thread.currentThread().isInterrupted())
      throw new CancellationException("Obsolete scene selection");
  }

  record Filtered<T>(List<T> sections, int candidates, int tested, int inside, int coarseTests) {}
}
