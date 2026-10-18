// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.cadence.metal.MetalPipelineMath;
import dev.cadence.metal.MetalPipelineResources;
import dev.cadence.shaders.compile.ShaderCompatibilityCompiler;
import dev.cadence.shaders.compile.TranslatedProgram;
import dev.cadence.shaders.compile.UniformLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** One translated program specialized for a draw format and immutable pipeline state. */
public record PackPipeline(
    PackPrograms.Source source,
    TranslatedProgram translated,
    CompiledRenderPipeline compiled,
    boolean fullscreen,
    boolean shadow,
    boolean depth,
    int depthTarget,
    int renderStage,
    float alphaTestRef,
    String primarySampler,
    java.util.List<SamplerBinding> samplerBindings,
    java.util.Set<String> readSamplers)
    implements AutoCloseable {

  public enum SamplerKind {
    COLOR,
    DEPTH,
    SHADOW_DEPTH,
    SHADOW_COLOR,
    NOISE,
    NORMAL,
    SPECULAR,
    MATERIAL,
    ALBEDO,
    LIGHTMAP,
    CUSTOM
  }

  public record SamplerBinding(
      String name, String shaderName, SamplerKind kind, int index, boolean comparison) {
    /**
     * Cadence: in a full-screen pass (composite, deferred, final) the legacy sampler `texture` is colortex0, the texture
     * on unit 0 there in OptiFine (Sildur's Enhanced reads its picture through it); it was given the white texture.
     */
    static SamplerBinding of(TranslatedProgram.Sampler sampler, boolean fullscreen) {
      SamplerBinding binding = of(sampler);
      if (fullscreen && binding.kind() == SamplerKind.ALBEDO)
        return new SamplerBinding(binding.name(), binding.shaderName(), SamplerKind.COLOR, 0, binding.comparison());
      // Cadence: in a world program, gcolor is texture unit 0, the block texture, as OptiFine binds samplers by unit (RRe36's
      // Nostalgia and Vanilla Plus read their albedo through it; as colortex0 every block was one flat colour)
      if (!fullscreen && binding.name().equals("gcolor"))
        return new SamplerBinding(binding.name(), binding.shaderName(), SamplerKind.ALBEDO, 0, binding.comparison());
      return binding;
    }

    static SamplerBinding of(TranslatedProgram.Sampler sampler) {
      String name = sampler.name();
      SamplerKind kind;
      int index = 0;
      if (name.matches("colortex[0-9]+|gcolor|gdepth|gnormal|composite|gaux[1-4]")) {
        kind = SamplerKind.COLOR;
        try {
          index = dev.cadence.shaders.pack.ShaderDirectives.colorBufferIndex(name);
        } catch (java.io.IOException failure) {
          throw new java.io.UncheckedIOException(failure);
        }
      } else if (name.matches("depthtex[012]")) {
        kind = SamplerKind.DEPTH;
        index = name.charAt(8) - '0';
      } else if (name.matches("shadowtex[01]")
          || name.equals("shadow")
          || name.equals("watershadow")) {
        kind = SamplerKind.SHADOW_DEPTH;
        index = name.endsWith("1") ? 1 : 0;
      } else if (name.matches("shadowcolor[0-7]") || name.equals("shadowcolor")) {
        kind = SamplerKind.SHADOW_COLOR;
        index = name.equals("shadowcolor") ? 0 : name.charAt(11) - '0';
      } else
        kind =
            switch (name) {
              case "noisetex" -> SamplerKind.NOISE;
              case "normals" -> SamplerKind.NORMAL;
              case "specular" -> SamplerKind.SPECULAR;
              case "materialtex" -> SamplerKind.MATERIAL;
              case "texture", "gtexture", "tex" -> SamplerKind.ALBEDO;
              case "lightmap" -> SamplerKind.LIGHTMAP;
              default -> SamplerKind.CUSTOM;
            };
      return new SamplerBinding(
          name,
          ShaderCompatibilityCompiler.samplerShaderName(name),
          kind,
          index,
          sampler.comparison());
    }
  }

  /** (measurement: pipelines built and their time, full-screen vs geometry) */
  public static final java.util.concurrent.atomic.AtomicLong STAT_SCREEN_NS = new java.util.concurrent.atomic.AtomicLong(),
      STAT_GEOM_NS = new java.util.concurrent.atomic.AtomicLong(), STAT_SCREEN_N = new java.util.concurrent.atomic.AtomicLong(),
      STAT_GEOM_N = new java.util.concurrent.atomic.AtomicLong();

  public static PackPipeline compile(
      GpuDevice device,
      PackPrograms.Source source,
      TranslatedProgram translated,
      RenderPipeline original,
      PackRenderTargets targets,
      boolean finalPass,
      boolean shadow,
      int depthTarget,
      int renderStage,
      float alphaTestRef) {
    long t0 = System.nanoTime();
    try {
      return compile0(device, source, translated, original, targets, finalPass, shadow, depthTarget, renderStage, alphaTestRef);
    } finally {
      long dt = System.nanoTime() - t0;
      if (original == null) { STAT_SCREEN_NS.addAndGet(dt); STAT_SCREEN_N.incrementAndGet(); }
      else { STAT_GEOM_NS.addAndGet(dt); STAT_GEOM_N.incrementAndGet(); }
    }
  }

  private static PackPipeline compile0(
      GpuDevice device,
      PackPrograms.Source source,
      TranslatedProgram translated,
      RenderPipeline original,
      PackRenderTargets targets,
      boolean finalPass,
      boolean shadow,
      int depthTarget,
      int renderStage,
      float alphaTestRef) {
    boolean fullscreen = original == null;
    // Depth-only utilities retain Safe math; ordinary pack color programs may opt in.
    boolean relaxedFragmentMath =
        source.relaxedFragmentMath() && !translated.drawBuffers().isEmpty();
    var builder =
        RenderPipeline.builder()
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "cadence_shaders",
                    translated
                            .label()
                            .toLowerCase(java.util.Locale.ROOT)
                            .replaceAll("[^a-z0-9/._-]", "_")
                        + (fullscreen ? "/screen" : "/" + original.getLocation().getPath())
                        + (relaxedFragmentMath ? "/math_relaxed_fragment" : "/math_safe")))
            .withVertexShader(Identifier.fromNamespaceAndPath("cadence_shaders", "pack"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("cadence_shaders", "pack"))
            .withPrimitiveTopology(
                fullscreen ? PrimitiveTopology.TRIANGLES : original.getPrimitiveTopology())
            .withCull(!fullscreen && original.isCull());
    Map<String, BindGroupLayout.UniformDescription> bindings = new LinkedHashMap<>();
    if (original != null) {
      for (var binding : BindGroupLayout.flattenUniforms(original.getBindGroupLayouts()))
        bindings.put(binding.name(), binding);
      for (int slot = 0; slot < original.getVertexFormatBindings().size(); slot++) {
        var format = original.getVertexFormatBindings().get(slot);
        if (format != null) builder.withVertexBinding(slot, format);
      }
      builder
          .withPolygonMode(original.getPolygonMode())
          .withPushConstantSize(original.pushConstantSize());
    }
    if (translated.uniforms().byteSize() > 0)
      bindings.put(
          UniformLayout.BLOCK_NAME,
          new BindGroupLayout.UniformDescription(
              UniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER));
    for (var sampler : translated.samplers()) {
      String name = ShaderCompatibilityCompiler.samplerShaderName(sampler.name());
      bindings.put(
          name, new BindGroupLayout.UniformDescription(name, UniformType.COMBINED_IMAGE_SAMPLER));
    }
    builder.withBindGroupLayout(new BindGroupLayout(java.util.List.copyOf(bindings.values())));
    var blend =
        original == null
                || original.getColorTargetStates().isEmpty()
                || original.getColorTargetStates().getFirst() == null
            ? Optional.<BlendFunction>empty()
            : original.getColorTargetStates().getFirst().blendFunction();
    for (int slot = 0; slot < translated.drawBuffers().size(); slot++) {
      int target = translated.drawBuffers().get(slot);
      if (target < 0 || shadow && !targets.shadowColorAttached(target, slot)) builder.withUnusedColorTargetState(slot);
      else {
        var format =
            finalPass
                ? com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM
                : shadow
                    ? targets.shadowColor(target).texture.getFormat()
                    : targets.format(target);
        // Cadence: an integer buffer (Noble's RGBA32UI material data) is never blended, as OpenGL ignores blending for
        // integer formats (Metal refuses it)
        boolean integer = format.name().endsWith("_UINT") || format.name().endsWith("_SINT");
        builder.withColorTargetState(
            slot,
            new ColorTargetState(
                // Cadence: blend.<program>[.<buffer>] from shaders.properties (PackBlend)
                finalPass || integer ? Optional.empty() : PackBlend.resolve(source.name(), target, shadow, fullscreen ? Optional.empty() : blend),
                format,
                ColorTargetState.WRITE_ALL));
      }
    }
    boolean depth = original != null && original.getDepthStencilState() != null;
    if (depth) {
      DepthStencilState state = original.getDepthStencilState();
      CompareOp compare =
          switch (state.depthTest()) {
            case GREATER_THAN -> CompareOp.LESS_THAN;
            case GREATER_THAN_OR_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
            case LESS_THAN -> CompareOp.GREATER_THAN;
            case LESS_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
            default -> state.depthTest();
          };
      builder.withDepthStencilState(
          new DepthStencilState(
              compare,
              state.writeDepth(),
              -state.depthBiasScaleFactor(),
              -state.depthBiasConstant()));
    }
    ShaderSource shaderSource =
        new ShaderSource() {
          @Override
          public String getShader(Identifier id, ShaderType type) {
            return type == ShaderType.VERTEX
                ? translated.vertexSource()
                : translated.fragmentSource();
          }

          @Override
          public CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          @Override
          public void close() {}
        };
    RenderPipeline description = builder.build();
    CompiledRenderPipeline compiled;
    // Direct executor and joined compilation keep the exact-name backend hint on this thread.
    // Vertex compilation remains Safe; unsupported precision contracts fall back in the backend.
    // Cadence: which samplers are always sampled alike, for passes past Metal's 16 sampler slots (MetalPipelineSamplers)
    Map<String, Object> samplerGroups = new java.util.HashMap<>();
    ShaderRuntime runtime = ShaderRuntime.get();
    if (runtime != null)
      for (TranslatedProgram.Sampler sampler : translated.samplers()) {
        Object group = runtime.samplerGroup(source, SamplerBinding.of(sampler, fullscreen));
        if (group != null) samplerGroups.put(SamplerBinding.of(sampler, fullscreen).shaderName(), group);
      }
    try (var math =
            relaxedFragmentMath
                ? MetalPipelineMath.openRelaxedFragment(description.getLocation().toString())
                : null;
        var groups = dev.cadence.metal.MetalPipelineSamplers.open(samplerGroups);
        // Cadence: the pack's images, buffer objects and 3D textures this program uses (set 1)
        var storage =
            dev.cadence.metal.MetalStorage.open(
                runtime == null || translated.storage().isEmpty() ? List.of() : runtime.storageSlots(translated))) {
      compiled =
          device.compilePipeline(description, shaderSource, Runnable::run).join().finishCompile();
    } catch (Exception e) {
      if (e instanceof RuntimeException r) throw r;
      throw new IllegalStateException(e);
    }
    if (compiled == null) {
      // -Dcadence.shaders.dumpFailedPipelines=DIR: the translated GLSL of a pipeline that didn't compile, for reading
      String dump = System.getProperty("cadence.shaders.dumpFailedPipelines");
      if (dump != null) {
        try {
          java.nio.file.Path dir = java.nio.file.Path.of(dump);
          java.nio.file.Files.createDirectories(dir);
          String base = translated.label().replaceAll("[^A-Za-z0-9_.-]", "_");
          java.nio.file.Files.writeString(dir.resolve(base + ".vsh"), translated.vertexSource());
          java.nio.file.Files.writeString(dir.resolve(base + ".fsh"), translated.fragmentSource());
        } catch (java.io.IOException | RuntimeException ignored) {
          // diagnostics only
        }
      }
      throw new IllegalStateException("Pack pipeline compilation failed: " + translated.label());
    }
    String primarySampler =
        original != null
                && original
                    .getShaders()
                    .get(ShaderType.VERTEX)
                    .getPath()
                    .equals("core/rendertype_end_portal")
            ? "Sampler1"
            : "Sampler0";
    // Strict frontend validation requires every declared binding, even when the compiled
    // shaders never read it. Unknown backends also retain all dependencies. Otherwise use the
    // backend's immutable activity facts to avoid preparing/binding unused pack textures.
    var activeResources =
        FrontendGpuDevice.STRICT_VALIDATION
            ? Optional.<java.util.Set<String>>empty()
            : MetalPipelineResources.activeResourceNames(compiled);
    var samplerBindings =
        translated.samplers().stream()
            .map(sampler -> SamplerBinding.of(sampler, fullscreen))
            .filter(
                sampler ->
                    activeResources.isEmpty()
                        || activeResources.get().contains(sampler.shaderName()))
            .toList();
    return new PackPipeline(
        source,
        translated,
        compiled,
        fullscreen,
        shadow,
        depth,
        depthTarget,
        renderStage,
        alphaTestRef,
        primarySampler,
        samplerBindings,
        readSet(translated, samplerBindings));
  }

  private static java.util.Set<String> readSet(TranslatedProgram translated, java.util.List<SamplerBinding> samplerBindings) {
    var out = samplerBindings.stream().map(SamplerBinding::name).filter(n -> reads(translated, shaderName(samplerBindings, n)))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (Boolean.getBoolean("cadence.shaders.logReads"))
      org.slf4j.LoggerFactory.getLogger("cadence_shaders").info("reads {}: {} of {}", translated.label(), new java.util.TreeSet<>(out),
          samplerBindings.stream().map(SamplerBinding::name).sorted().toList());
    return out;
  }

  private static String shaderName(java.util.List<SamplerBinding> bindings, String name) {
    for (SamplerBinding b : bindings) if (b.name().equals(name)) return b.shaderName();
    return name;
  }

  /** Cadence: whether this program's code samples the named sampler (not only declares it: see reads). */
  public boolean readsSampler(SamplerBinding sampler) {
    return readSamplers.contains(sampler.name());
  }

  /**
   * Cadence: whether the program's code uses this sampler beyond declaring it. Packs declare every buffer in shared
   * includes: Complementary's world programs declare colortex0, 3, 4 and 6 (and depthtex0) while drawing into them, which
   * cost a full-size copy of each per phase (ShaderRuntime.prepareFeedback) though most never read them.
   */
  static boolean reads(TranslatedProgram translated, String name) {
    java.util.regex.Pattern token = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b");
    java.util.regex.Pattern declaration =
        java.util.regex.Pattern.compile("\\buniform\\s+\\w+\\s+" + java.util.regex.Pattern.quote(name) + "\\s*;");
    for (String source : new String[] {translated.vertexSource(), translated.fragmentSource()}) {
      if (source == null) continue;
      if (token.matcher(source).results().count() > declaration.matcher(source).results().count()) return true;
    }
    return false;
  }

  @Override
  public void close() {
    compiled.close();
  }
}
