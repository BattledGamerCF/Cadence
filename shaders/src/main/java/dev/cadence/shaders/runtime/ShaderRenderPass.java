// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cadence.shaders.geometry.FeatureDrawContext;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.lwjgl.PointerBuffer;

/**
 * Adapts one logical Minecraft pass to the pack's attachment groups. Draw buffers and indirect
 * commands are forwarded unchanged; only a target change or an explicit graph boundary splits it.
 */
public final class ShaderRenderPass implements RenderPass {
  public record TextureBinding(GpuTextureView texture, GpuSampler sampler) {}

  private final ShaderRuntime runtime;
  private final Map<String, GpuBufferSlice> buffers = new LinkedHashMap<>();
  private final Map<String, TextureBinding> textures = new LinkedHashMap<>();
  private final GpuBufferSlice[] vertices = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
  private final List<Supplier<String>> groups = new ArrayList<>();
  private GpuBuffer index;
  private IndexType indexType;
  private ByteBuffer constants;
  private int[] scissor;
  private RenderPass delegate;
  private PackPipeline pipeline;
  private PackPipeline bound;
  private FeatureDrawContext.DrawTag boundTag;
  private boolean closed;
  private boolean skipDraw;

  public static volatile java.util.Set<String> SKIP_PROGRAMS =
      java.util.Set.of(System.getProperty("cadence.shaders.skipPrograms", "").split(",")).stream()
          .filter(n -> !n.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());

  public ShaderRenderPass(ShaderRuntime runtime) {
    this.runtime = runtime;
    runtime.opened(this);
  }

  public Map<String, TextureBinding> textures() {
    return textures;
  }

  private RenderPass ready() {
    if (closed) throw new IllegalStateException("Shader render pass is closed");
    if (pipeline == null) throw new IllegalStateException("No scene pipeline selected");
    if (delegate == null) {
      runtime.prepare(pipeline);
      delegate = runtime.open(pipeline);
      for (var group : groups) delegate.pushDebugGroup(group);
      for (var uniform : buffers.entrySet())
        delegate.setUniform(uniform.getKey(), uniform.getValue());
      for (var uniform : textures.entrySet())
        delegate.setUniform(
            uniform.getKey(), uniform.getValue().texture(), uniform.getValue().sampler());
      for (int slot = 0; slot < vertices.length; slot++) {
        if (vertices[slot] != null) delegate.setVertexBuffer(slot, vertices[slot]);
      }
      if (index != null) delegate.setIndexBuffer(index, indexType);
      if (scissor != null) delegate.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
      bound = null;
    }
    if (bound != pipeline) {
      delegate.setPipeline(pipeline.compiled());
      if (constants != null
          && pipeline.compiled()
              instanceof com.mojang.renderpearl.frontend.FrontendRenderPipeline frontend
          && frontend.pushConstantSize() > 0) delegate.pushConstants(constants.duplicate());
      runtime.bind(pipeline, delegate, textures);
      bound = pipeline;
      boundTag = FeatureDrawContext.currentDrawTag();
    } else if (!FeatureDrawContext.currentDrawTag().equals(boundTag)) {
      // Prepared feature groups can share a pipeline while changing material IDs or damage tint.
      runtime.bindUniforms(pipeline, delegate);
      boundTag = FeatureDrawContext.currentDrawTag();
    }
    return delegate;
  }

  /** Cadence (HiZ): the depth texture the current pack pipeline writes, or null. */
  public com.mojang.renderpearl.api.textures.GpuTexture depthTexture() {
    if (pipeline == null || !pipeline.depth()) return null;
    return runtime.targets().depth(pipeline.depthTarget()).texture;
  }

  /** End the native pass before depth snapshots or screen passes; keep the logical draw state. */
  public void suspend() {
    if (delegate != null) {
      for (int i = groups.size() - 1; i >= 0; i--) delegate.popDebugGroup();
      delegate.close();
      delegate = null;
      bound = null;
    }
  }

  /**
   * Cadence: a draw the pack can't make (a program the GPU compiler refused, more resources than the device has) is
   * skipped, with the rest of its pipeline's draws, and the pack goes off between frames. Throwing here would leave the
   * game's frame half done (the next one then fails in the game's own code).
   */
  private void failed(RuntimeException failure) {
    dev.cadence.shaders.cadence.ShaderSwitcher.failedInFrame(failure);
    skipDraw = true;
  }

  @Override
  public void setPipeline(CompiledRenderPipeline original) {
    PackPipeline next;
    try {
      var description = OriginalPipelines.get(original);
      skipDraw = runtime.isShadow() && ShadowRenderer.skipPipeline(description);
      if (skipDraw) return;
      next = runtime.pipeline(description);
    } catch (RuntimeException failure) {
      failed(failure);
      return;
    }
    // Cadence (debugging): -Dcadence.shaders.skipPrograms=a,b / self-test skipprograms=a+b: those programs draw nothing
    if (!SKIP_PROGRAMS.isEmpty() && SKIP_PROGRAMS.contains(next.source().name())) {
      skipDraw = true;
      return;
    }
    // Cadence: a draw whose program writes no buffer and has no depth (Bliss's sky with its writes switched off) changes nothing
    if (!next.depth() && next.translated().drawBuffers().stream().noneMatch(index -> index >= 0)) {
      skipDraw = true;
      return;
    }
    if (pipeline != null
        && (!pipeline.translated().drawBuffers().equals(next.translated().drawBuffers())
            || pipeline.depth() != next.depth()
            || pipeline.depthTarget() != next.depthTarget()
            || pipeline.shadow() != next.shadow()
            || pipeline != next && runtime.requiresMipGeneration(next)
            || runtime.requiresReadPreparation(next))) suspend();
    pipeline = next;
  }

  @Override
  public void setUniform(String name, GpuTextureView texture, GpuSampler sampler) {
    textures.put(name, new TextureBinding(texture, sampler));
    if (delegate != null) {
      delegate.setUniform(name, texture, sampler);
      if (bound != null) runtime.bindSuppliedTexture(bound, delegate, textures, name);
    }
  }

  @Override
  public void setUniform(String name, GpuBuffer buffer) {
    setUniform(name, buffer.slice());
  }

  @Override
  public void setUniform(String name, GpuBufferSlice buffer) {
    buffers.put(name, buffer);
    if (delegate != null) delegate.setUniform(name, buffer);
  }

  @Override
  public void setVertexBuffer(int slot, GpuBufferSlice buffer) {
    vertices[slot] = buffer;
    if (delegate != null) delegate.setVertexBuffer(slot, buffer);
  }

  @Override
  public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
    index = buffer;
    indexType = type;
    if (delegate != null) delegate.setIndexBuffer(buffer, type);
  }

  @Override
  public void pushConstants(ByteBuffer values) {
    if (constants == null || constants.capacity() < values.remaining())
      constants = ByteBuffer.allocateDirect(values.remaining());
    constants.clear().put(values.duplicate()).flip();
    if (delegate != null) delegate.pushConstants(constants.duplicate());
  }

  @Override
  public void enableScissor(int x, int y, int width, int height) {
    scissor = new int[] {x, y, width, height};
    if (delegate != null) delegate.enableScissor(x, y, width, height);
  }

  @Override
  public void disableScissor() {
    scissor = null;
    if (delegate != null) delegate.disableScissor();
  }

  @Override
  public void pushDebugGroup(Supplier<String> name) {
    groups.add(name);
    if (delegate != null) delegate.pushDebugGroup(name);
  }

  @Override
  public void popDebugGroup() {
    if (groups.isEmpty()) throw new IllegalStateException("No debug group to close");
    groups.removeLast();
    if (delegate != null) delegate.popDebugGroup();
  }

  @Override
  public void writeTimestamp(GpuQueryPool pool, int query) {
    if (delegate != null) delegate.writeTimestamp(pool, query);
    else runtime.device().createCommandEncoder().writeTimestamp(pool, query);
  }

  @Override
  public void drawIndexed(int a, int b, int c, int d, int e) {
    if (!skipDraw)
      try {
        ready().drawIndexed(a, b, c, d, e);
      } catch (RuntimeException failure) {
        failed(failure);
      }
  }

  @Override
  public void multiDrawIndexed(IntBuffer a, int b, int c, int d) {
    if (!skipDraw)
      try {
        ready().multiDrawIndexed(a, b, c, d);
      } catch (RuntimeException failure) {
        failed(failure);
      }
  }

  @Override
  public void multiDrawIndexed(PointerBuffer a, IntBuffer b, IntBuffer c, int d) {
    if (!skipDraw)
      try {
        ready().multiDrawIndexed(a, b, c, d);
      } catch (RuntimeException failure) {
        failed(failure);
      }
  }

  @Override
  public void drawIndexedIndirect(GpuBufferSlice a, int b) {
    if (!skipDraw)
      try {
        ready().drawIndexedIndirect(a, b);
      } catch (RuntimeException failure) {
        failed(failure);
      }
  }

  @Override
  public <T> void drawMultipleIndexed(
      Collection<Draw<T>> a, GpuBuffer b, IndexType c, Collection<String> d, T e) {
    if (!skipDraw)
      try {
        ready().drawMultipleIndexed(a, b, c, d, e);
      } catch (RuntimeException failure) {
        failed(failure);
      }
  }

  @Override
  public void draw(int a, int b, int c, int d) {
    if (!skipDraw)
      try {
        ready().draw(a, b, c, d);
      } catch (RuntimeException failure) {
        failed(failure);
      }
  }

  @Override
  public void multiDraw(IntBuffer a, int b, int c, int d) {
    if (!skipDraw)
      try {
        ready().multiDraw(a, b, c, d);
      } catch (RuntimeException failure) {
        failed(failure);
      }
  }

  @Override
  public void multiDraw(IntBuffer a, IntBuffer b, int c) {
    if (!skipDraw)
      try {
        ready().multiDraw(a, b, c);
      } catch (RuntimeException failure) {
        failed(failure);
      }
  }

  @Override
  public void drawIndirect(GpuBufferSlice a, int b) {
    if (!skipDraw)
      try {
        ready().drawIndirect(a, b);
      } catch (RuntimeException failure) {
        failed(failure);
      }
  }

  @Override
  public void close() {
    if (closed) return;
    suspend();
    closed = true;
    runtime.closed(this);
  }
}
