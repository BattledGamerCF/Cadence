// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene;

import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;

/**
 * Ordered, immutable membership for immediate render-thread preparation. Section objects still
 * belong to Minecraft; this is not permission to retain meshes across frames or read them on a
 * worker. Camera, shadow and other views never share a mutable membership list.
 */
public record SceneSelection(
    ViewArea area,
    SceneGeneration generation,
    String viewId,
    List<RenderSection> sections,
    int candidates,
    int boundsTests,
    int coarseTests,
    String providerId) {
  public SceneSelection {
    Objects.requireNonNull(area, "area");
    Objects.requireNonNull(generation, "generation");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(providerId, "providerId");
    sections = List.copyOf(sections);
    if (candidates < sections.size() || boundsTests < 0 || coarseTests < 0) {
      throw new IllegalArgumentException("Invalid scene-selection counters");
    }
  }
}
