// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.geometry;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.resources.Identifier;

/** Retains the real model normals already supplied by vanilla's dynamic block mesher. */
public final class DynamicBlockGeometry {
  public static final int NORMAL_OFFSET = 28;
  public static final int STRIDE = 32;
  public static final VertexFormat FORMAT =
      VertexFormat.builder(0)
          .addAttribute("Position", GpuFormat.RGB32_FLOAT)
          .addAttribute("Color", GpuFormat.RGBA8_UNORM)
          .addAttribute("UV0", GpuFormat.RG32_FLOAT)
          .addAttribute("UV2", GpuFormat.RG16_SINT)
          .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
          .build();

  private static final Map<RenderPipeline, RenderPipeline> PIPELINES = new IdentityHashMap<>();
  private static final Map<OitPipelineSet, OitPipelineSet> OIT_PIPELINES = new IdentityHashMap<>();

  private DynamicBlockGeometry() {}

  public static synchronized RenderPipeline pipeline(RenderPipeline original) {
    if (!TerrainShaderGeometry.isEnabled() || !needsNormals(original)) return original;
    return PIPELINES.computeIfAbsent(original, DynamicBlockGeometry::extend);
  }

  /** Cadence: the extended version whether or not a pack is on (PipelineMemory), or null if this pipeline has none. */
  public static synchronized RenderPipeline extended(RenderPipeline original) {
    return needsNormals(original) ? PIPELINES.computeIfAbsent(original, DynamicBlockGeometry::extend) : null;
  }

  public static synchronized OitPipelineSet pipelines(OitPipelineSet original) {
    if (original == null || !TerrainShaderGeometry.isEnabled()) return original;
    if (!needsNormals(original.depthBoundsPipeline())
        && !needsNormals(original.transmittancePipeline())
        && !needsNormals(original.accumulatePipeline())) return original;
    return OIT_PIPELINES.computeIfAbsent(
        original,
        source ->
            new OitPipelineSet(
                pipeline(source.depthBoundsPipeline()),
                pipeline(source.transmittancePipeline()),
                pipeline(source.accumulatePipeline())));
  }

  private static boolean needsNormals(RenderPipeline pipeline) {
    Identifier shader = pipeline.getShaders().get(ShaderType.VERTEX);
    if (shader == null || !shader.getNamespace().equals("minecraft")) return false;
    return (shader.getPath().equals("core/block")
            && pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.BLOCK)
        || (shader.getPath().equals("core/rendertype_end_portal")
            && pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.POSITION);
  }

  private static RenderPipeline extend(RenderPipeline original) {
    VertexFormat[] formats = original.getVertexFormatBindings().toArray(VertexFormat[]::new);
    formats[0] =
        original.getShaders().get(ShaderType.VERTEX).getPath().equals("core/rendertype_end_portal")
            ? PortalGeometry.FORMAT
            : FORMAT;
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
                "cadence_shaders", "dynamic_block/" + original.getLocation().getPath()))
        .build();
  }
}
