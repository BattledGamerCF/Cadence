// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene;

import java.util.Objects;
import net.minecraft.client.renderer.ViewArea;

/**
 * One view's independent scene requirements. Refinement is optional and may remove primary-volume
 * candidates only when the caller has established that this is valid for the pack/pass.
 */
public record SceneViewRequest(
    ViewArea area,
    SceneGeneration generation,
    String viewId,
    SceneVolume volume,
    SceneVolume refinement,
    boolean requireRenderableLayers) {
  public SceneViewRequest {
    Objects.requireNonNull(area, "area");
    Objects.requireNonNull(generation, "generation");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(volume, "volume");
  }
}
