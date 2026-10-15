// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import java.util.concurrent.atomic.LongAdder;

/** Counters for the opt-in prototype; lookups occur on the render thread, invalidations may not. */
public final class DrawMetadataMetrics {
  public static final boolean ENABLED = Boolean.getBoolean("cadence.scene.drawMetadataCache");
  static long sliceHits;
  static long sliceMisses;
  static long drawHits;
  static long drawMisses;
  static long infoHits;
  static long infoMisses;
  static final LongAdder invalidations = new LongAdder();
  static final LongAdder retainedSlices = new LongAdder();
  static final LongAdder retainedDraws = new LongAdder();
  static final LongAdder retainedInfos = new LongAdder();

  private DrawMetadataMetrics() {}

  public static Stats snapshot() {
    return new Stats(
        ENABLED,
        sliceHits,
        sliceMisses,
        drawHits,
        drawMisses,
        infoHits,
        infoMisses,
        invalidations.sum(),
        retainedSlices.sum(),
        retainedDraws.sum(),
        retainedInfos.sum());
  }

  public record Stats(
      boolean enabled,
      long sliceHits,
      long sliceMisses,
      long drawHits,
      long drawMisses,
      long sectionInfoHits,
      long sectionInfoMisses,
      long allocationInvalidations,
      long retainedSliceEntries,
      long retainedDrawEntries,
      long retainedSectionInfos) {}
}
