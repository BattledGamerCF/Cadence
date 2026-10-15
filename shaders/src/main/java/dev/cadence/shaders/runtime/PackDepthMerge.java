// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;

/** Reunites late opaque-hand depth with scene depth without sampling the render attachment. */
public final class PackDepthMerge implements AutoCloseable {
  private static final String VERTEX =
      """
      #version 450
      void main() {
        vec2 corner = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
        gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
      }
      """;
  private static final String FRAGMENT =
      """
      #version 450
      uniform sampler2D SceneDepth;
      uniform sampler2D OpaqueDepth;
      void main() {
        ivec2 pixel = ivec2(gl_FragCoord.xy);
        gl_FragDepth = min(texelFetch(SceneDepth, pixel, 0).r,
                           texelFetch(OpaqueDepth, pixel, 0).r);
      }
      """;
  private final GpuDevice device;
  private final GpuSampler sampler;
  private final CompiledRenderPipeline pipeline;
  private boolean closed;

  public PackDepthMerge(GpuDevice device) {
    this.device = device;
    sampler =
        device.createSampler(
            AddressMode.CLAMP_TO_EDGE,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.NEAREST,
            FilterMode.NEAREST,
            1,
            OptionalDouble.of(0));
    try {
      var description =
          RenderPipeline.builder()
              .withLocation(
                  Identifier.fromNamespaceAndPath("cadence_shaders", "depth_merge"))
              .withVertexShader(
                  Identifier.fromNamespaceAndPath("cadence_shaders", "depth_merge"))
              .withFragmentShader(
                  Identifier.fromNamespaceAndPath("cadence_shaders", "depth_merge"))
              .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
              .withCull(false)
              .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
              .withBindGroupLayout(
                  BindGroupLayout.builder()
                      .withUniform("SceneDepth", UniformType.COMBINED_IMAGE_SAMPLER)
                      .withUniform("OpaqueDepth", UniformType.COMBINED_IMAGE_SAMPLER)
                      .build())
              .build();
      ShaderSource source =
          new ShaderSource() {
            public String getShader(Identifier id, ShaderType type) {
              return type == ShaderType.VERTEX ? VERTEX : FRAGMENT;
            }

            public CachedIncludeSource getInclude(Identifier id) {
              return null;
            }

            public void close() {}
          };
      pipeline = device.compilePipeline(description, source, Runnable::run).join().finishCompile();
      if (pipeline == null)
        throw new IllegalStateException("Depth merge pipeline compilation failed");
    } catch (RuntimeException failure) {
      sampler.close();
      throw failure;
    }
  }

  public void merge(PackRenderTargets targets) {
    if (closed) throw new IllegalStateException("Pack depth merger is closed");
    targets.ensureDefined(targets.depth(0));
    targets.ensureDefined(targets.depth(1));
    var descriptor =
        RenderPassDescriptor.builder(() -> "Shader-pack hand depth merge")
            .withDepthAttachment(targets.depthMergeTarget().level(0), OptionalDouble.of(1))
            .build();
    try (var pass = device.createCommandEncoder().createRenderPass(descriptor)) {
      pass.setPipeline(pipeline);
      pass.setUniform("SceneDepth", targets.depth(0).sampled, sampler);
      pass.setUniform("OpaqueDepth", targets.depth(1).sampled, sampler);
      pass.draw(3, 1, 0, 0);
    }
    // Conventional shader-pack depth has near=0. Preserve opaque+hand (1) and opaque-only (2).
    targets.commitMergedDepth();
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    pipeline.close();
    sampler.close();
  }
}
