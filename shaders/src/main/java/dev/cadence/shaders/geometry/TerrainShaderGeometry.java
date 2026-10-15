// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.geometry;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/** Pack attributes added during vanilla chunk compilation, without changing its draw batching. */
public final class TerrainShaderGeometry {
  public static final int STRIDE = 64;
  public static final int NORMAL_OFFSET = 28;
  public static final int ENTITY_OFFSET = 32;
  public static final int MID_UV_OFFSET = 40;
  public static final int TANGENT_OFFSET = 48;
  public static final int MID_BLOCK_OFFSET = 52;
  public static final VertexFormat FORMAT =
      VertexFormat.builder(0)
          .addAttribute("Position", GpuFormat.RGB32_FLOAT)
          .addAttribute("Color", GpuFormat.RGBA8_UNORM)
          .addAttribute("UV0", GpuFormat.RG32_FLOAT)
          .addAttribute("UV2", GpuFormat.RG16_SINT)
          .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
          .addAttribute("PackEntity", GpuFormat.RG32_FLOAT)
          .addAttribute("PackMidUV", GpuFormat.RG32_FLOAT)
          .addAttribute("PackTangent", GpuFormat.RGBA8_SNORM)
          .addAttribute("PackMidBlock", GpuFormat.RGB32_FLOAT)
          .build();
  public static final VertexFormat COMPACT_FORMAT =
      VertexFormat.builder(0)
          .addAttribute("Position", GpuFormat.RGB32_FLOAT)
          .addAttribute("Color", GpuFormat.RGBA8_UNORM)
          .addAttribute("UV0", GpuFormat.RG32_FLOAT)
          .addAttribute("UV2", GpuFormat.RG16_SINT)
          .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
          .addAttribute("PackMetadata", GpuFormat.R32_UINT)
          // Cadence: the texture midpoint as 16-bit fractions of the atlas (x/65536): exact for pixel-aligned block
          // models (their midpoints are multiples of 1/65536 up to 16384-wide atlases), within 1/64 of a texture pixel
          // otherwise; 4 bytes per vertex less (48 -> 44)
          .addAttribute("PackMidUV", GpuFormat.RG16_UINT)
          .addAttribute("PackTangent", GpuFormat.RGBA8_SNORM)
          .build();

  /** Immutable layouts: an in-flight worker never observes a changed stride or attribute offset. */
  public enum Layout {
    FULL(FORMAT, STRIDE, MID_UV_OFFSET, TANGENT_OFFSET),
    COMPACT(COMPACT_FORMAT, 44, 36, 40);

    private final VertexFormat format;
    private final int stride;
    private final int midUvOffset;
    private final int tangentOffset;

    Layout(VertexFormat format, int stride, int midUvOffset, int tangentOffset) {
      this.format = format;
      this.stride = stride;
      this.midUvOffset = midUvOffset;
      this.tangentOffset = tangentOffset;
    }

    public VertexFormat format() {
      return format;
    }

    public int stride() {
      return stride;
    }

    /** The texture midpoint is stored as two 16-bit fractions (x/65536), not floats. */
    public boolean packedMidUv() {
      return this == COMPACT;
    }

    public int midUvOffset() {
      return midUvOffset;
    }

    public int tangentOffset() {
      return tangentOffset;
    }
  }

  private static final Map<RenderPipeline, EnumMap<Layout, RenderPipeline>> PIPELINES =
      new IdentityHashMap<>();
  private static final ThreadLocal<SectionContext> SECTION = new ThreadLocal<>();
  private static volatile Configuration configuration;

  private TerrainShaderGeometry() {}

  /**
   * Call before entering a level, or after disposing its section dispatcher and stopping its
   * workers. The resolver must be immutable and thread safe; an unmapped block has ID -1.
   */
  public static void configure(ToIntFunction<BlockState> resolver) {
    configure(resolver, true);
  }

  public static void configure(ToIntFunction<BlockState> resolver, boolean separateAo) {
    configure(resolver, separateAo, false);
  }

  /** The range proof must cover every possible resolver result, including unmapped states. */
  public static synchronized void configure(
      ToIntFunction<BlockState> resolver, boolean separateAo, boolean allIdsFitSigned16) {
    dev.cadence.scene.SceneViews.materialsChanged();
    Layout layout =
        allIdsFitSigned16
                // Cadence: compact (48-byte) records by default; lossless, -25% terrain vertex memory.
                && !"false".equals(System.getProperty("cadence.shaders.compactTerrainVertices"))
            ? Layout.COMPACT
            : Layout.FULL;
    PIPELINES.clear();
    configuration = new Configuration(Objects.requireNonNull(resolver), separateAo, layout);
  }

  /**
   * Requires the same full dispatcher disposal as configure; changing only the compiler is unsafe.
   */
  public static synchronized void disable() {
    dev.cadence.scene.SceneViews.materialsChanged();
    PIPELINES.clear();
    configuration = null;
  }

  /**
   * Cadence: would terrain compiled under configuration {@code a} be byte-identical under {@code b}? Same vertex layout,
   * same AO mode, and the same pack id for every block state (only BlockMaterialMap resolvers can be compared).
   */
  public static boolean sameGeometry(Object a, Object b) {
    return geometryDifference(a, b) == null;
  }

  /** Cadence: null when terrain built under either configuration is identical, else what differs. */
  public static String geometryDifference(Object a, Object b) {
    if (!(a instanceof Configuration x) || !(b instanceof Configuration y)) return "shaders turned on or off";
    if (x.layout != y.layout) return "vertex layout " + x.layout + " -> " + y.layout;
    if (x.separateAo != y.separateAo) return "separateAo changed";
    if (!(x.resolver instanceof dev.cadence.shaders.runtime.BlockMaterialMap mx)
        || !(y.resolver instanceof dev.cadence.shaders.runtime.BlockMaterialMap my)) return "unknown block id mapping";
    int changed = mx.differingBlockIds(my);
    return changed == 0 ? null : changed + " block states changed pack id";
  }

  /** Cadence: the current configuration (opaque; for sameGeometry), null when no pack terrain is active. */
  public static Object configurationKey() {
    return configuration;
  }

  public static boolean isEnabled() {
    return configuration != null;
  }

  public static Layout layout() {
    SectionContext context = SECTION.get();
    Configuration settings =
        context != null ? context.settings : VANILLA_SECTION.get() != null ? null : configuration;
    return settings == null ? Layout.FULL : settings.layout;
  }

  public static Layout layoutFor(VertexFormat format) {
    return format == FORMAT ? Layout.FULL : format == COMPACT_FORMAT ? Layout.COMPACT : null;
  }

  /** Each worker keeps the same material mapping for the whole section compilation. */
  public static SectionContext beginSection() {
    SectionContext previous = SECTION.get();
    Configuration settings = configuration;
    if (settings == null) {
      SECTION.remove();
      // Cadence: the section was started with no pack; it stays a vanilla section even if a pack is switched on before it
      // asks for its layers' vertex format (otherwise the format would be the pack's with no pack context: a crash).
      VANILLA_SECTION.set(Boolean.TRUE);
    } else {
      SECTION.set(new SectionContext(settings));
      VANILLA_SECTION.remove();
    }
    return previous;
  }

  public static void endSection(SectionContext previous) {
    VANILLA_SECTION.remove();
    if (previous == null) SECTION.remove();
    else SECTION.set(previous);
  }

  private static final ThreadLocal<Boolean> VANILLA_SECTION = new ThreadLocal<>();

  /**
   * Cadence: whether pack terrain applies to the calling thread: inside a section compile, that section's snapshot decides
   * (a live shader switch can happen while workers are mid-section); elsewhere, the current configuration.
   */
  public static boolean isEnabledForThread() {
    if (SECTION.get() != null) return true;
    if (VANILLA_SECTION.get() != null) return false;
    return configuration != null;
  }

  public static boolean hasSection() {
    return SECTION.get() != null;
  }

  public static boolean separateAo() {
    SectionContext context = SECTION.get();
    return context != null && context.settings.separateAo;
  }

  public static void beginBlock(BlockState state, BlockPos pos, boolean fluid) {
    SectionContext context = SECTION.get();
    if (context == null) return;
    // Cadence: this runs for every block of the section, air included; the material lookup (10% of chunk-worker time) is
    // deferred to the first vertex the block emits (currentBlock), which yields the same values.
    context.pendingState = state;
    context.pendingFluid = fluid;
    context.blockX = pos.getX() & 15;
    context.blockY = pos.getY() & 15;
    context.blockZ = pos.getZ() & 15;
    context.resolved = false;
    context.hasBlock = true;
  }

  public static void endBlock() {
    SectionContext context = SECTION.get();
    if (context != null) context.hasBlock = false;
  }

  public static SectionContext currentBlock() {
    SectionContext context = SECTION.get();
    if (context == null || !context.hasBlock) {
      throw new IllegalStateException("Shader terrain vertex has no block/material context");
    }
    if (!context.resolved) context.resolve();
    return context;
  }

  /**
   * The pipeline layout also controls arena allocation alignment and indirect baseVertex
   * arithmetic. Keep the extended stride even before the runtime substitutes a compiled pack
   * pipeline.
   */
  public static synchronized RenderPipeline pipeline(RenderPipeline original) {
    if (!isEnabled()) return original;
    Layout layout = layout();
    return PIPELINES
        .computeIfAbsent(original, unused -> new EnumMap<>(Layout.class))
        .computeIfAbsent(layout, selected -> extendPipeline(original, selected));
  }

  private static RenderPipeline extendPipeline(RenderPipeline original, Layout layout) {
    VertexFormat[] formats = original.getVertexFormatBindings().toArray(VertexFormat[]::new);
    formats[0] = layout.format;
    ColorTargetState[] colors = original.getColorTargetStates().toArray(ColorTargetState[]::new);
    RenderPipeline.Snippet snippet =
        new RenderPipeline.Snippet(
            original.getShaders(),
            Optional.of(original.getShaderDefines()),
            Optional.of(original.getBindGroupLayouts()),
            colors,
            colors.length,
            Optional.ofNullable(original.getDepthStencilState()),
            Optional.of(original.getPolygonMode()),
            Optional.of(original.isCull()),
            formats,
            Optional.of(original.getPrimitiveTopology()),
            original.pushConstantSize());
    return RenderPipeline.builder(snippet)
        .withLocation(
            Identifier.fromNamespaceAndPath(
                "cadence_shaders",
                "geometry/"
                    + original.getLocation().getPath()
                    + (layout == Layout.COMPACT ? "/compact44" : "")))
        .build();
  }

  public static final class SectionContext {
    private final Configuration settings;
    private boolean hasBlock;
    private int packedMetadata;
    public int materialId;
    public int renderType;
    public float midX;
    public float midY;
    public float midZ;

    private BlockState pendingState;
    private boolean pendingFluid, resolved;
    private int blockX, blockY, blockZ;

    private SectionContext(Configuration settings) {
      this.settings = settings;
    }

    /** The block's pack metadata, computed once for the block's first vertex (beginBlock only records the block). */
    private void resolve() {
      materialId = settings.resolver.applyAsInt(pendingState);
      renderType = pendingFluid ? 1 : -1;
      midX = blockX + 0.5f;
      midY = blockY + 0.5f;
      midZ = blockZ + 0.5f;
      if (settings.layout == Layout.COMPACT) {
        if (materialId < Short.MIN_VALUE || materialId > Short.MAX_VALUE)
          throw new IllegalStateException("Compact terrain material range proof was violated");
        packedMetadata =
            (materialId & 0xffff)
                | (pendingFluid ? 1 << 16 : 0)
                | (blockX << 17)
                | (blockY << 21)
                | (blockZ << 25);
      }
      resolved = true;
    }

    public Layout layout() {
      return settings.layout;
    }

    public int packedMetadata() {
      return packedMetadata;
    }
  }

  private record Configuration(
      ToIntFunction<BlockState> resolver, boolean separateAo, Layout layout) {}
}
