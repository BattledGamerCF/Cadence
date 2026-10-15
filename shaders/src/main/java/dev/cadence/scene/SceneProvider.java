// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene;

/** Exactly one provider owns scene selection. Calls and publication occur on the render thread. */
public interface SceneProvider {
  String id();

  SceneSelection select(SceneViewRequest request);

  /** Optional same-frame preparation. Unsupported providers keep ordinary immediate selection. */
  default SceneSelectionTicket prefetch(SceneViewRequest request) {
    return null;
  }

  /** Consume once if ready and valid, otherwise select current state without waiting. */
  default SceneSelection select(SceneViewRequest request, SceneSelectionTicket ticket) {
    return select(request);
  }

  /** Providers may retain event-driven membership; the default performs conservative selection. */
  default java.util.List<net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection>
      selectExtraction(SceneExtractionRequest request) {
    return request.merge(select(request.view()).sections());
  }

  /** Release all retained world and material state. Also called for unload and resource reload. */
  default void invalidate() {}
}
