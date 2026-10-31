// Adapted from minecraft-metal-renderer (MIT, Copyright (c) 2026 Minecraft Metal Renderer contributors),
// https://github.com/Kausik-Velaga/minecraft-metal-renderer — see shaders/LICENSE-MIT-shader-loader.
package dev.cadence.metal.backend;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.Objects;

/** Explicit caller proofs for operations absent from the general render API. */
public final class MetalPassHints {
  public static final String DISCARD_COLOR_CAPABILITY = "color-attachment-load-discard";
  /**
   * Cadence: honour discard hints (MetalEncoder.createRenderPass) and advertise {@link #DISCARD_COLOR_CAPABILITY}. Exact: only
   * attachments the caller proved fully overwritten. -Dcadence.cadence.discardLoads=false turns it off for A/B runs.
   */
  static final boolean ENABLED =
      !"false".equals(System.getProperty("cadence.cadence.discardLoads"));
  private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

  private MetalPassHints() {}

  /**
   * The caller proves that its draws overwrite every pixel/component of the selected attachments,
   * without reading their old contents. The hint applies once, only to this descriptor instance
   * during synchronous createRenderPass. It does not authorize changing draw order or store
   * actions.
   */
  public static Scope openDiscardColors(RenderPassDescriptor descriptor, int mask) {
    Objects.requireNonNull(descriptor, "descriptor");
    if ((mask & ~255) != 0)
      throw new IllegalArgumentException("Color mask exceeds eight attachments");
    Scope scope = new Scope(descriptor, mask, CURRENT.get());
    CURRENT.set(scope);
    return scope;
  }

  static int consumeDiscardColors(RenderPassDescriptor descriptor) {
    Scope scope = CURRENT.get();
    if (scope == null || scope.consumed || scope.descriptor != descriptor) return 0;
    scope.consumed = true;
    if (descriptor.depthAttachment() != null) return 0;
    var attachments = descriptor.colorAttachments();
    if (attachments.size() > 8) return 0;
    int width = 0, height = 0;
    for (var attachment : attachments) {
      if (attachment == null) continue;
      var view = attachment.textureView();
      if (view.baseMipLevel() != 0
          || view.texture().getDepthOrLayers() != 1
          || (view.texture().usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) return 0;
      if (width != 0 && (width != view.getWidth(0) || height != view.getHeight(0))) return 0;
      width = view.getWidth(0);
      height = view.getHeight(0);
    }
    if (width <= 0 || height <= 0) return 0;
    var area = descriptor.renderArea();
    if (area != null
        && (area.x() != 0 || area.y() != 0 || area.width() != width || area.height() != height))
      return 0;
    int valid = 0;
    for (int i = 0; i < attachments.size(); i++) {
      var attachment = attachments.get(i);
      if (attachment != null && attachment.clearValue().isEmpty()) valid |= 1 << i;
    }
    return scope.mask & valid;
  }

  public static final class Scope implements AutoCloseable {
    private final RenderPassDescriptor descriptor;
    private final int mask;
    private final Scope previous;
    private final Thread owner = Thread.currentThread();
    private boolean consumed, closed;

    private Scope(RenderPassDescriptor descriptor, int mask, Scope previous) {
      this.descriptor = descriptor;
      this.mask = mask;
      this.previous = previous;
    }

    @Override
    public void close() {
      if (closed) return;
      if (owner != Thread.currentThread() || CURRENT.get() != this)
        throw new IllegalStateException(
            "Metal pass hints must close on their owner thread in nesting order");
      closed = true;
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }
}
