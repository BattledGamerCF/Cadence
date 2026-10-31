// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;

/** Mip generation on the GPU, retaining raster filtering for irregular-size reductions. */
public final class PackMipmaps implements AutoCloseable {
  private static final String VERTEX =
      """
      #version 450
      layout(location=0) out vec2 uv;
      void main() {
        uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
        gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
      }
      """;
  private static final String FRAGMENT =
      """
      #version 450
      layout(location=0) in vec2 uv;
      layout(location=0) out vec4 color;
      uniform sampler2D Source;
      void main() { color = textureLod(Source, uv, 0.0); }
      """;
  private final GpuDevice device;
  private final boolean nativeGeneration;
  private final Map<GpuFormat, CompiledRenderPipeline> pipelines = new EnumMap<>(GpuFormat.class);
  private final GpuSampler sampler;
  private boolean closed;

  public PackMipmaps(GpuDevice device) {
    this(device, Boolean.getBoolean("cadence.shaders.nativeMipmaps"));
  }

  public PackMipmaps(GpuDevice device, boolean nativeGeneration) {
    this.device = device;
    this.nativeGeneration = nativeGeneration;
    sampler =
        device.createSampler(
            AddressMode.CLAMP_TO_EDGE,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR,
            FilterMode.LINEAR,
            1,
            OptionalDouble.of(0));
    try {
      // Compile at load time; texture generation never creates a pipeline during a frame.
      for (GpuFormat format :
          List.of(
              GpuFormat.R8_UNORM,
              GpuFormat.RG8_UNORM,
              GpuFormat.RGBA8_UNORM,
              GpuFormat.RGBA16_UNORM,
              // Cadence: signed and 16-bit normalized buffers too (AstraLex mips an RG16_SNORM one)
              GpuFormat.R8_SNORM,
              GpuFormat.RG8_SNORM,
              GpuFormat.RGBA8_SNORM,
              GpuFormat.R16_UNORM,
              GpuFormat.RG16_UNORM,
              GpuFormat.R16_SNORM,
              GpuFormat.RG16_SNORM,
              GpuFormat.RGBA16_SNORM,
              GpuFormat.R16_FLOAT,
              GpuFormat.RG16_FLOAT,
              GpuFormat.RGBA16_FLOAT,
              GpuFormat.R32_FLOAT,
              GpuFormat.RG32_FLOAT,
              GpuFormat.RGBA32_FLOAT,
              GpuFormat.RG11B10_FLOAT,
              GpuFormat.RGB10A2_UNORM)) {
        var pipeline =
            RenderPipeline.builder()
                .withLocation(
                    Identifier.fromNamespaceAndPath(
                        "cadence_shaders",
                        "mipmap/" + format.name().toLowerCase(java.util.Locale.ROOT)))
                .withVertexShader(
                    Identifier.fromNamespaceAndPath("cadence_shaders", "mipmap"))
                .withFragmentShader(
                    Identifier.fromNamespaceAndPath("cadence_shaders", "mipmap"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(
                    new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(
                    BindGroupLayout.builder()
                        .withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER)
                        .build())
                .withCull(false)
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
        CompiledRenderPipeline compiled =
            device.compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
        if (compiled == null)
          throw new IllegalStateException("Mipmap pipeline compilation failed for " + format);
        pipelines.put(format, compiled);
      }
    } catch (RuntimeException failure) {
      close();
      throw failure;
    }
  }

  /** Cadence: -Dcadence.shaders.nativeMipmaps=false: one render pass per level, as before. */
  private static final boolean BLIT_MIPS = !"false".equals(System.getProperty("cadence.shaders.nativeMipmaps"));

  public void generate(PackTexture texture) {
    if (closed) throw new IllegalStateException("Pack mipmap generator is closed");
    if (texture.texture.getMipLevels() <= 1) return;
    // Cadence: the whole chain in one blit command (Metal's box filter, what OpenGL's glGenerateMipmap gives on this
    // hardware) instead of a render pass per level, for formats Metal filters on every Apple GPU
    if (BLIT_MIPS && blitFormat(texture.texture.getFormat())) {
      dev.cadence.metal.MetalCompute.generateMipmaps(texture.texture);
      return;
    }
    int firstRasterLevel = 1;
    // Cadence: Cadence's backend has no native partial-mip kernel yet; the raster filter below builds every level.
    if (false && nativeGeneration && nativeFormat(texture.texture.getFormat())) {
      int width = texture.texture.getWidth(0), height = texture.texture.getHeight(0);
      // Metal's NPOT kernel has different interpolation precision. Only batch exact 2:1
      // reductions; resume the established raster filter at the first irregular dimension.
      while (firstRasterLevel < texture.texture.getMipLevels()
          && (width == 1 || (width & 1) == 0)
          && (height == 1 || (height & 1) == 0)) {
        firstRasterLevel++;
        width = Math.max(1, width / 2);
        height = Math.max(1, height / 2);
      }
      if (firstRasterLevel == texture.texture.getMipLevels()) return;
    }
    CompiledRenderPipeline pipeline = pipelines.get(texture.texture.getFormat());
    if (pipeline == null)
      throw new UnsupportedOperationException("Mipmap format " + texture.texture.getFormat());
    var encoder = device.createCommandEncoder();
    for (int level = firstRasterLevel; level < texture.texture.getMipLevels(); level++) {
      int destination = level;
      // Views restrict reads and writes to distinct levels of the same allocation.
      try (var pass =
          encoder.createRenderPass(
              () -> "Shader-pack mip " + destination,
              texture.level(destination),
              Optional.of(new org.joml.Vector4f()))) {
        pass.setPipeline(pipeline);
        pass.setUniform("Source", texture.level(destination - 1), sampler);
        pass.draw(3, 1, 0, 0);
      }
    }
  }

  /**
   * Cadence: source's base level drawn, filtered, over all of destination's (same format, any size): a history buffer
   * carried across a render-scale change. False when the format has no pipeline here.
   */
  public boolean blit(PackTexture source, PackTexture destination) {
    if (closed) throw new IllegalStateException("Pack mipmap generator is closed");
    CompiledRenderPipeline pipeline = pipelines.get(destination.texture.getFormat());
    if (pipeline == null || source.texture.getFormat() != destination.texture.getFormat()) return false;
    var encoder = device.createCommandEncoder();
    try (var pass =
        encoder.createRenderPass(() -> "Shader-pack history rescale", destination.level(0), Optional.empty())) {
      pass.setPipeline(pipeline);
      pass.setUniform("Source", source.level(0), sampler);
      pass.draw(3, 1, 0, 0);
    }
    return true;
  }

  private static boolean blitFormat(GpuFormat format) {
    return switch (format) {
      case R8_UNORM, RG8_UNORM, RGBA8_UNORM, RGBA16_UNORM, R16_FLOAT, RG16_FLOAT, RGBA16_FLOAT, RG11B10_FLOAT, RGB10A2_UNORM,
          R8_SNORM, RG8_SNORM, RGBA8_SNORM, R16_UNORM, RG16_UNORM, R16_SNORM, RG16_SNORM, RGBA16_SNORM -> true;
      default -> false;
    };
  }

  private static boolean nativeFormat(GpuFormat format) {
    return format == GpuFormat.RGBA8_UNORM
        || format == GpuFormat.RGBA16_FLOAT
        || format == GpuFormat.RG11B10_FLOAT;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    pipelines.values().forEach(CompiledRenderPipeline::close);
    pipelines.clear();
    sampler.close();
  }
}
