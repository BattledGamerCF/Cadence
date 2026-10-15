// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene;

import java.util.ArrayList;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;

/** Conservative adapter available when the optional scene optimizer is absent. */
public final class VanillaSceneProvider implements SceneProvider {
  @Override
  public String id() {
    return "vanilla";
  }

  @Override
  public SceneSelection select(SceneViewRequest request) {
    var selected = new ArrayList<RenderSection>();
    int candidates = 0;
    int boundsTests = 0;
    for (var section : SceneViews.sections(request.area())) {
      if (request.requireRenderableLayers() && !section.getSectionMesh().hasRenderableLayers()) {
        continue;
      }
      var bounds = section.getBoundingBox();
      boundsTests++;
      if (!request.volume().intersects(bounds)) continue;
      candidates++;
      if (request.refinement() == null || request.refinement().intersects(bounds)) {
        selected.add(section);
      }
    }
    return new SceneSelection(
        request.area(),
        request.generation(),
        request.viewId(),
        selected,
        candidates,
        boundsTests,
        0,
        id());
  }
}
