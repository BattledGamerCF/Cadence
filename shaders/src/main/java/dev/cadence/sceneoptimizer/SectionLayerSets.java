// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * Shared immutable layer subsets: no array allocation in per-section, per-view draw preparation.
 */
public final class SectionLayerSets {
  private static final ChunkSectionLayer[] ALL = ChunkSectionLayer.values();
  private static final ChunkSectionLayer[][] SETS = buildSets();

  private SectionLayerSets() {}

  public static ChunkSectionLayer[] forMask(int mask) {
    return SETS[mask];
  }

  public static int count() {
    return ALL.length;
  }

  private static ChunkSectionLayer[][] buildSets() {
    var sets = new ChunkSectionLayer[1 << ALL.length][];
    for (int mask = 0; mask < sets.length; mask++) {
      var layers = new ChunkSectionLayer[Integer.bitCount(mask)];
      int target = 0;
      for (ChunkSectionLayer layer : ALL) {
        if ((mask & (1 << layer.ordinal())) != 0) layers[target++] = layer;
      }
      sets[mask] = layers;
    }
    return sets;
  }
}
