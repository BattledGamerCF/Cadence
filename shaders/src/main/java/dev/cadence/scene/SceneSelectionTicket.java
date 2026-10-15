// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene;

/**
 * Opaque, single-use preparation token. Only its creating provider may consume it, with the exact
 * same request object. A missing, obsolete or unfinished token must never require waiting.
 */
public interface SceneSelectionTicket extends AutoCloseable {
  /** Discard on the owning render thread; idempotent and never cancels a replacement ticket. */
  @Override
  void close();
}
