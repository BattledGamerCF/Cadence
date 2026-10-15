// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;

/** A single active view-area listener. No events or section references accumulate without queries. */
public final class SectionMeshChanges {
  private static volatile EventSectionMembership<RenderSection> listener;

  private SectionMeshChanges() {}

  public static void listen(EventSectionMembership<RenderSection> membership) {
    listener = membership;
  }

  public static void stop(EventSectionMembership<RenderSection> membership) {
    if (listener == membership) listener = null;
  }

  public static void changed(RenderSection section) {
    var current = listener;
    if (current != null) current.changed(section);
  }
}
