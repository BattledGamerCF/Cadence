// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene;

import net.minecraft.world.phys.AABB;

/**
 * A conservative intersection test in world coordinates. If this rejects a box, it must also reject
 * every box contained by that box. Providers may test unions before individual sections. Capture
 * stable view inputs; implementations must not mutate the scene during selection.
 */
@FunctionalInterface
public interface SceneVolume {
  boolean intersects(AABB bounds);

  /**
   * Optional privately owned immutable predicate for worker queries. It must preserve both tests
   * exactly and must never read live game state. Returning this is valid only for immutable
   * implementations; arbitrary predicates and custom volumes remain render-thread-only.
   */
  default SceneVolume workerSnapshot() {
    return null;
  }

  /**
   * Full containment is optional. Generic predicates only prove rejection or intersection; they
   * never imply that every contained section can skip its own test.
   */
  default Classification classify(AABB bounds) {
    return intersects(bounds) ? Classification.INTERSECT : Classification.OUTSIDE;
  }

  enum Classification {
    OUTSIDE,
    INTERSECT,
    INSIDE
  }
}
