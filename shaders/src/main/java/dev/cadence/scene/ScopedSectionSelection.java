// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterators;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;

/** Exception-safe draw-preparation input override; the renderer's camera list remains untouched. */
public final class ScopedSectionSelection implements AutoCloseable {
  private static final ThreadLocal<ScopedSectionSelection> ACTIVE = new ThreadLocal<>();
  private final Thread owner = Thread.currentThread();
  private final LevelRenderer renderer;
  private final String viewId;
  private final List<RenderSection> sections;
  private final ScopedSectionSelection previous;
  private boolean closed;

  private ScopedSectionSelection(LevelRenderer renderer, List<RenderSection> sections, String viewId) {
    this.renderer = Objects.requireNonNull(renderer);
    this.viewId = Objects.requireNonNull(viewId);
    this.sections = Objects.requireNonNull(sections);
    previous = ACTIVE.get();
    ACTIVE.set(this);
  }

  static ScopedSectionSelection open(
      LevelRenderer renderer, List<RenderSection> sections, String viewId) {
    return new ScopedSectionSelection(renderer, sections, viewId);
  }

  /** Null identifies ordinary camera preparation; another renderer never inherits this scope. */
  public static String viewId(LevelRenderer renderer) {
    ScopedSectionSelection active = ACTIVE.get();
    return active != null && active.renderer == renderer ? active.viewId : null;
  }

  public static ObjectListIterator<RenderSection> iterator(
      LevelRenderer renderer, ObjectArrayList<RenderSection> original) {
    ScopedSectionSelection active = ACTIVE.get();
    return active != null && active.renderer == renderer
        ? ObjectIterators.asObjectIterator(active.sections.listIterator())
        : original.iterator();
  }

  @Override
  public void close() {
    if (closed) return;
    if (owner != Thread.currentThread() || ACTIVE.get() != this) {
      throw new IllegalStateException("Scene selection scopes must close in order on their owner");
    }
    closed = true;
    if (previous == null) ACTIVE.remove();
    else ACTIVE.set(previous);
  }
}
