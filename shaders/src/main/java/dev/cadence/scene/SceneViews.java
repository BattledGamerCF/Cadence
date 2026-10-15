// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.cadence.scene.mixin.ViewAreaSceneAccessor;
import java.util.Objects;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.joml.Matrix4fc;

/**
 * Small scene contract hosted once in the backend artifact. It owns no pack semantics and no GPU
 * implementation; the default adapter and optional scene mod serve the same caller requirements.
 */
public final class SceneViews {
  private static final SceneProvider VANILLA = new VanillaSceneProvider();
  private static SceneProvider provider = VANILLA;
  private static long worldGeneration;
  private static long materialGeneration;

  private SceneViews() {}

  /**
   * Register during client initialization, before scene use. Competing providers fail explicitly.
   */
  public static void register(SceneProvider replacement) {
    Objects.requireNonNull(replacement, "replacement");
    if (provider != VANILLA && provider != replacement) {
      throw new IllegalStateException("A scene provider is already registered: " + provider.id());
    }
    provider = replacement;
  }

  public static String providerId() {
    return provider.id();
  }

  public static SceneGeneration generation() {
    return new SceneGeneration(worldGeneration, materialGeneration);
  }

  public static Iterable<RenderSection> sections(ViewArea area) {
    return ((ViewAreaSceneAccessor) area).scene$sections();
  }

  public static RenderSection section(ViewArea area, long sectionNode) {
    return ((ViewAreaSceneAccessor) area).scene$getRenderSection(sectionNode);
  }

  public static SceneSelection select(SceneViewRequest request) {
    return select(request, null);
  }

  /** The request and its snapshots belong to this frame; no live state may escape to a worker. */
  public static SceneSelectionTicket prefetch(SceneViewRequest request) {
    requireCurrent(request.generation());
    return provider.prefetch(request);
  }

  public static SceneSelection select(SceneViewRequest request, SceneSelectionTicket ticket) {
    requireCurrent(request.generation());
    SceneSelection result = provider.select(request, ticket);
    if (result.area() != request.area()
        || !result.generation().equals(request.generation())
        || !result.viewId().equals(request.viewId())) {
      throw new IllegalStateException("Scene provider returned another view or generation");
    }
    return result;
  }

  public static java.util.List<RenderSection> selectExtraction(SceneExtractionRequest request) {
    requireCurrent(request.view().generation());
    return provider.selectExtraction(request);
  }

  /** Uses Minecraft's normal draw preparation and lifetime handling with independent membership. */
  public static ChunkSectionsToRender prepare(
      LevelRenderer renderer,
      SceneSelection selection,
      Matrix4fc cameraViewRotation,
      boolean sortTranslucent) {
    requireCurrent(selection.generation());
    if (renderer.viewArea() != selection.area()) {
      throw new IllegalStateException("Scene selection belongs to a replaced view area");
    }
    ChunkSectionsToRender prepared;
    try (var ignored = ScopedSectionSelection.open(renderer, selection.sections(), selection.viewId())) {
      prepared = renderer.isChunkRenderingUsingMultiDrawIndirect()
          ? renderer.prepareChunkRendersIndirect(cameraViewRotation, sortTranslucent)
          : renderer.prepareChunkRenders(cameraViewRotation, sortTranslucent);
    }
    // Vanilla preparation only requests sequential-index capacity. Its normal frame preparation
    // commits those requests before drawing, but independent views can be prepared after that
    // boundary and introduce a larger mesh. Realize the cumulative request before returning a
    // drawable view; renderLayers deliberately reads getBuffer() without growing it itself.
    RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).resizeToRequestedIndexCount();
    return prepared;
  }

  /** The loader calls this after stopping old meshing work and before publishing new materials. */
  public static void materialsChanged() {
    materialGeneration++;
    provider.invalidate();
  }

  /** Called by the renderer lifecycle adapter, including world unload and geometry invalidation. */
  public static void worldChanged() {
    worldGeneration++;
    provider.invalidate();
  }

  private static void requireCurrent(SceneGeneration generation) {
    if (generation.world() != worldGeneration || generation.materials() != materialGeneration) {
      throw new IllegalStateException("Discarded scene selection from an obsolete generation");
    }
  }
}
