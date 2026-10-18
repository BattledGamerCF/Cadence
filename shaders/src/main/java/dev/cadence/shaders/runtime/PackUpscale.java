// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
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
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;
import net.minecraft.resources.Identifier;

/**
 * Cadence render scale: the pack's whole frame (final pass included) is drawn at a lower resolution, then this pass draws it
 * to the window at full size. Catmull-Rom bicubic reconstruction (sharper than bilinear stretching, no ringing worth
 * mentioning at these ratios), then a light contrast-adaptive sharpen whose strength is a compile-time constant chosen per
 * scale (more sharpening the lower the scale). One fullscreen triangle into the main target; the GUI is drawn after it at
 * full resolution, so text stays crisp.
 */
public final class PackUpscale implements AutoCloseable {
  private static final String VERTEX =
      """
      #version 450
      layout(location=0) out vec2 uv;
      void main() {
        uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
        gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
      }
      """;
  // Catmull-Rom from 16 texel fetches (exact, no reliance on bilinear tricks), clamped to the 2x2 neighbourhood range to
  // avoid overshoot halos; then sharpen = centre + k * (centre - blur), with k scaled down where local contrast is high.
  private static final String FRAGMENT_TEMPLATE =
      """
      #version 450
      layout(location=0) in vec2 uv;
      layout(location=0) out vec4 color;
      uniform sampler2D Source;
      const float SHARPEN = %SHARPEN%;
      vec4 fetch(ivec2 p, ivec2 size) { return texelFetch(Source, clamp(p, ivec2(0), size - 1), 0); }
      vec4 cubicRow(vec4 a, vec4 b, vec4 c, vec4 d, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5 * ((2.0 * b) + (-a + c) * t + (2.0 * a - 5.0 * b + 4.0 * c - d) * t2 + (-a + 3.0 * b - 3.0 * c + d) * t3);
      }
      void main() {
        ivec2 size = textureSize(Source, 0);
        // uv maps source to destination one to one (as PackMipmaps' copy does), so orientation is preserved.
        vec2 p = uv * vec2(size) - 0.5;
        ivec2 i = ivec2(floor(p));
        vec2 f = p - vec2(i);
        vec4 r0 = cubicRow(fetch(i + ivec2(-1,-1), size), fetch(i + ivec2(0,-1), size), fetch(i + ivec2(1,-1), size), fetch(i + ivec2(2,-1), size), f.x);
        vec4 r1 = cubicRow(fetch(i + ivec2(-1, 0), size), fetch(i + ivec2(0, 0), size), fetch(i + ivec2(1, 0), size), fetch(i + ivec2(2, 0), size), f.x);
        vec4 r2 = cubicRow(fetch(i + ivec2(-1, 1), size), fetch(i + ivec2(0, 1), size), fetch(i + ivec2(1, 1), size), fetch(i + ivec2(2, 1), size), f.x);
        vec4 r3 = cubicRow(fetch(i + ivec2(-1, 2), size), fetch(i + ivec2(0, 2), size), fetch(i + ivec2(1, 2), size), fetch(i + ivec2(2, 2), size), f.x);
        vec4 c = cubicRow(r0, r1, r2, r3, f.y);
        vec4 n00 = fetch(i, size), n10 = fetch(i + ivec2(1, 0), size), n01 = fetch(i + ivec2(0, 1), size), n11 = fetch(i + ivec2(1, 1), size);
        vec4 lo = min(min(n00, n10), min(n01, n11)), hi = max(max(n00, n10), max(n01, n11));
        c = clamp(c, lo, hi);
        if (SHARPEN > 0.0) {
          vec4 blur = mix(mix(n00, n10, f.x), mix(n01, n11, f.x), f.y);
          float contrast = dot(hi.rgb - lo.rgb, vec3(0.3333));
          float k = SHARPEN * (1.0 - clamp(contrast * 2.0, 0.0, 1.0));
          c = clamp(c + k * (c - blur), 0.0, 1.0);
        }
        color = vec4(c.rgb, 1.0);
      }
      """;

  private final CompiledRenderPipeline pipeline;
  private final GpuSampler sampler;
  private final float sharpen;

  // Cadence: "Fast" upscaler, one texture tap per pixel. Sharp bilinear: each world texel stays a solid block and only the
  // pixel-wide seam between two texels is blended (Minecraft is pixel art; plain bilinear blurred every edge, MetalFX costs
  // 1-2 ms at Retina size). At a whole-number ratio (50%) the seams vanish and it is exactly nearest-texel.
  private static final String FAST_FRAGMENT =
      """
      #version 450
      layout(location=0) in vec2 uv;
      layout(location=0) out vec4 color;
      uniform sampler2D Source;
      void main() {
        vec2 size = vec2(textureSize(Source, 0));
        vec2 texel = uv * size;
        vec2 scale = max(vec2(1.0), 1.0 / max(fwidth(texel), vec2(1e-4)));
        vec2 base = floor(texel);
        vec2 centre = fract(texel) - 0.5;
        vec2 range = 0.5 - 0.5 / scale;
        vec2 f = (centre - clamp(centre, -range, range)) * scale + 0.5;
        color = vec4(texture(Source, (base + f) / size).rgb, 1.0);
      }
      """;

  private final boolean fast;

  public PackUpscale(GpuDevice device, GpuFormat format, float sharpen) {
    this(device, format, sharpen, false);
  }

  public PackUpscale(GpuDevice device, GpuFormat format, float sharpen, boolean fast) {
    this.sharpen = sharpen;
    this.fast = fast;
    FilterMode filter = fast ? FilterMode.LINEAR : FilterMode.NEAREST;
    sampler =
        device.createSampler(
            AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, filter, filter, 1,
            OptionalDouble.of(0));
    String fragment = fast ? FAST_FRAGMENT : FRAGMENT_TEMPLATE.replace("%SHARPEN%", String.format(java.util.Locale.ROOT, "%.3f", sharpen));
    var description =
        RenderPipeline.builder()
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "cadence_shaders",
                    "cadence_upscale/" + format.name().toLowerCase(java.util.Locale.ROOT)
                        + (fast ? "_sharpbilinear" : "_" + Math.round(sharpen * 100))))
            .withVertexShader(Identifier.fromNamespaceAndPath("cadence_shaders", "cadence_upscale"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("cadence_shaders", "cadence_upscale"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
            .withBindGroupLayout(
                BindGroupLayout.builder().withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withCull(false)
            .build();
    ShaderSource source =
        new ShaderSource() {
          public String getShader(Identifier id, ShaderType type) {
            return type == ShaderType.VERTEX ? VERTEX : fragment;
          }

          public CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          public void close() {}
        };
    pipeline = device.compilePipeline(description, source, Runnable::run).join().finishCompile();
    if (pipeline == null) throw new IllegalStateException("Cadence upscale pipeline compilation failed");
  }

  public boolean fast() {
    return fast;
  }

  public float sharpen() {
    return sharpen;
  }

  /** Draws {@code source} (the pack's reduced-size output) over all of {@code destination}. */
  public void run(GpuDevice device, GpuTextureView source, GpuTextureView destination) {
    run(device, source, destination, null);
  }

  /**
   * Cadence (WorldScale): with {@code clearDepth}, that depth target is attached too and cleared to 0 (vanilla's clear), so
   * the passes that follow on the same targets (the hand, the world's text, the HUD) continue this pass instead of storing
   * the full-size image and loading it back.
   */
  public void run(GpuDevice device, GpuTextureView source, GpuTextureView destination, @Nullable GpuTextureView clearDepth) {
    var builder = RenderPassDescriptor.builder(() -> "Cadence render-scale upscale").withColorAttachment(destination);
    if (clearDepth != null) builder.withDepthAttachment(clearDepth, OptionalDouble.of(0));
    var descriptor = builder.build();
    try (var pass = device.createCommandEncoder().createRenderPass(descriptor)) {
      pass.setPipeline(pipeline);
      pass.setUniform("Source", source, sampler);
      pass.draw(3, 1, 0, 0);
    }
  }

  @Override
  public void close() {
    sampler.close();
  }
}
