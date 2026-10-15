// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;

/** Main-thread tracker ownership; moving recycled states cannot remove another state's new node. */
public final class DirtySectionMembership {
  private final Long2ObjectOpenHashMap<Object> dirty = new Long2ObjectOpenHashMap<>();
  /** Cadence: bumped whenever the set of dirty nodes changes (a node added or removed). */
  private long version;

  public void update(long node, Object state, boolean isDirty) {
    if (isDirty) {
      if (dirty.put(node, state) == null) version++;
    } else remove(node, state);
  }

  public void remove(long node, Object state) {
    if (dirty.get(node) == state) {
      dirty.remove(node);
      version++;
    }
  }

  public long version() {
    return version;
  }

  /** Consumed completely before vanilla extraction starts clearing dirty states. */
  public LongSet nodes() {
    return dirty.keySet();
  }
}
