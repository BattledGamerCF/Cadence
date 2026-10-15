// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import net.minecraft.client.renderer.DynamicGpuData.ChunkSectionInfo;

/**
 * Retains only exact immutable values; callers still calculate current origin and fade each view.
 */
public final class SectionInfoCache implements AutoCloseable {
  private ChunkSectionInfo previous;

  public ChunkSectionInfo find(int x, int y, int z, float visibility) {
    if (previous != null
        && previous.x() == x
        && previous.y() == y
        && previous.z() == z
        && Float.floatToRawIntBits(previous.visibility()) == Float.floatToRawIntBits(visibility)) {
      DrawMetadataMetrics.infoHits++;
      return previous;
    }
    DrawMetadataMetrics.infoMisses++;
    return null;
  }

  public void remember(ChunkSectionInfo info) {
    if (previous == null) DrawMetadataMetrics.retainedInfos.increment();
    previous = info;
  }

  @Override
  public void close() {
    if (previous != null) {
      previous = null;
      DrawMetadataMetrics.retainedInfos.decrement();
    }
  }
}
