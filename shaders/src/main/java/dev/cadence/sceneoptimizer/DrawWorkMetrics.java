// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * Optional cumulative prepared terrain work, counted before metadata-cache lookup. Replaying a
 * prepared view does not prepare another descriptor, so coverage-pass replays are counted by the
 * loader separately. Fixed buckets cannot retain per-world/mesh/view history.
 */
public final class DrawWorkMetrics {
  public static final boolean ENABLED = Boolean.getBoolean("cadence.scene.drawWorkCounters");
  private static final Accumulator TOTALS = new Accumulator();

  private DrawWorkMetrics() {}

  public static void record(String viewId, ChunkSectionLayer layer, int indices, int instances) {
    TOTALS.record(viewId, layer, indices, instances);
  }

  public static Stats snapshot() {
    return TOTALS.snapshot(ENABLED);
  }

  public record Stats(
      boolean enabled, Map<String, Long> preparedDraws, Map<String, Long> preparedIndices) {}

  static final class Accumulator {
    private static final String[] VIEWS = {"camera", "shadow", "other"};
    private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
    private final AtomicLongArray draws = new AtomicLongArray(VIEWS.length * LAYERS.length);
    private final AtomicLongArray indices = new AtomicLongArray(VIEWS.length * LAYERS.length);

    void record(String viewId, ChunkSectionLayer layer, int indexCount, int instanceCount) {
      if (indexCount <= 0 || instanceCount <= 0) return;
      int view = viewId == null ? 0 : viewId.equals("shadow-render") ? 1 : 2;
      int slot = view * LAYERS.length + layer.ordinal();
      draws.incrementAndGet(slot);
      indices.addAndGet(slot, (long) indexCount * instanceCount);
    }

    Stats snapshot(boolean enabled) {
      var drawSnapshot = new LinkedHashMap<String, Long>();
      var indexSnapshot = new LinkedHashMap<String, Long>();
      for (int view = 0; view < VIEWS.length; view++) {
        for (var layer : LAYERS) {
          int slot = view * LAYERS.length + layer.ordinal();
          String key = VIEWS[view] + "." + layer.name().toLowerCase(java.util.Locale.ROOT);
          drawSnapshot.put(key, draws.get(slot));
          indexSnapshot.put(key, indices.get(slot));
        }
      }
      return new Stats(enabled, Map.copyOf(drawSnapshot), Map.copyOf(indexSnapshot));
    }
  }
}
