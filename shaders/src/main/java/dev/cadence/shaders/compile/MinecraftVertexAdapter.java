// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.compile;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bridges actual Minecraft draw buffers to the legacy pack vertex interface entirely on GPU. */
public final class MinecraftVertexAdapter {
  public static final float HAND_DEPTH = 0.125f;

  private MinecraftVertexAdapter() {}

  /**
   * The pack's sky fragment shader needs coverage through the entire horizon, beyond vanilla's sky
   * disk.
   */
  public static ShaderCompatibilityCompiler.VertexAdapter skyBackground() {
    return new ShaderCompatibilityCompiler.VertexAdapter(
        "vec4 sl_BackgroundPosition;\n",
        "const vec2 sl_BackgroundCorners[6] = vec2[6](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 0.0),"
            + " vec2(1.0, 1.0), vec2(0.0, 1.0));\n"
            + "vec2 sl_BackgroundUv = sl_BackgroundCorners[gl_VertexIndex];\n"
            // Stay just inside the far clip plane so inverse infinite-far projections have
            // finite homogeneous W in packs which reconstruct and divide before normalizing.
            + "sl_BackgroundPosition = vec4(sl_BackgroundUv * 2.0 - 1.0, 0.999999, 1.0);\n",
        Map.of(
            "gl_Vertex",
            "sl_BackgroundPosition",
            "gl_ModelViewMatrix",
            "mat4(1.0)",
            "gl_ProjectionMatrix",
            "mat4(1.0)",
            "gl_ModelViewProjectionMatrix",
            "mat4(1.0)",
            // Cadence: the fog colour, what the game clears to behind its sky disc, not white. Packs tell stars from
            // the sky by a grey vertex colour (Sildur's Enhanced Default: r == g == b > 0 is a star, drawn in its own
            // colour) or draw the vertex colour as the sky (VanillaEX): white made the horizon gap the sky disc leaves a
            // white band in both.
            "gl_Color",
            "vec4(sl_Fog_color.rgb, 1.0)"),
        List.of(),
        Map.of("sl_Fog_color", new UniformLayout.Declaration("vec4", 0)));
  }

  /** Cadence: the far terrain's buffers in a dh_* program (DistantTerrain binds them): set-1 buffer indices. */
  public static final int FAR_QUADS = 200, FAR_ARGS = 201;

  /**
   * Cadence: a pack's dh_terrain program (Iris's Distant Horizons contract) drawing Cadence's far terrain. No vertex buffers:
   * the vertex stage reads its quad from the far terrain's culled records (dev.cadence.metal.lod.LodPackFrame; columns.metal
   * lod_mesh_pack), instance i holding quads [64 i, 64 i + 64), four vertices each. gl_Vertex is the corner relative to the
   * camera (as Distant Horizons gives it), gl_Color the quad's colour, gl_Normal its face's, gl_MultiTexCoord1 the sky light,
   * dhMaterialId a DH_BLOCK_* material; the matrices are the frame's model-view and dhProjection. A record past this frame's
   * count, or an empty one, has all four vertices at the origin with w 0: nothing is drawn.
   */
  public static ShaderCompatibilityCompiler.VertexAdapter distantTerrain() {
    String declarations =
        "layout(std430, binding = " + FAR_QUADS + ") readonly buffer CadenceFarQuads { uvec4 cadence_farQuads[]; };\n"
            + "layout(std430, binding = " + FAR_ARGS + ") readonly buffer CadenceFarArgs { uint cadence_farArgs[]; };\n"
            + """
            mat4 sl_PackTextureMatrices[8];
            vec4 sl_DhVertex;
            vec4 sl_DhColor;
            vec3 sl_DhNormal;
            vec4 sl_DhLight;
            int sl_DhMaterial;
            void sl_DhDecode() {
              int q = gl_InstanceIndex * 64 + (gl_VertexIndex >> 2), corner = gl_VertexIndex & 3;
              uvec4 r = q < int(cadence_farArgs[14]) ? cadence_farQuads[q] : uvec4(0u, 0u, 7u << 24, 0u);
              uint face = (r.z >> 24) & 7u;
              sl_DhVertex = vec4(0.0);
              sl_DhColor = vec4(1.0);
              sl_DhNormal = vec3(0.0, 1.0, 0.0);
              sl_DhLight = vec4(0.0, 240.0, 0.0, 1.0);
              sl_DhMaterial = 0;
              if (face > 5u) return;
              int x0 = int(r.x << 15) >> 15, dx = int(r.x >> 17), z0 = int(r.y << 15) >> 15, dz = int(r.y >> 17);
              int y0 = int(r.z & 0xFFFu) - 512, y1 = int((r.z >> 12) & 0xFFFu) - 512;
              bool a = corner == 1 || corner == 2, up = corner >= 2;
              ivec3 c;
              if (face == 0u) c = ivec3(up ? x0 + dx : x0, y0, a ? z0 + dz : z0);
              else if (face == 5u) c = ivec3(a ? x0 + dx : x0, y0, up ? z0 + dz : z0);
              else if (face == 1u) c = ivec3(x0, up ? y1 : y0, a ? z0 : z0 + dz);
              else if (face == 2u) c = ivec3(x0, up ? y1 : y0, a ? z0 + dz : z0);
              else if (face == 3u) c = ivec3(a ? x0 + dx : x0, up ? y1 : y0, z0);
              else c = ivec3(a ? x0 : x0 + dx, up ? y1 : y0, z0);
              vec3 cam = vec3(uintBitsToFloat(cadence_farArgs[116]), uintBitsToFloat(cadence_farArgs[117]),
                  uintBitsToFloat(cadence_farArgs[118]));
              vec3 rel = vec3(float(c.x), float(c.y - int(cadence_farArgs[119])), float(c.z)) - cam;
              // merged quads meet at T-junctions: each grows a hair in its own plane so no pixel falls between them
              float eps = 0.0005 + length(rel) * 3e-6;
              if (face == 0u || face == 5u) {
                rel.x += c.x > x0 ? eps : -eps;
                rel.z += c.z > z0 ? eps : -eps;
              } else if (face <= 2u) {
                rel.z += c.z > z0 ? eps : -eps;
                rel.y += up ? eps : -eps;
              } else {
                rel.x += c.x > x0 ? eps : -eps;
                rel.y += up ? eps : -eps;
              }
              rel.y -= uintBitsToFloat(cadence_farArgs[120]) * dot(rel.xz, rel.xz);   // the earth's curvature (0: flat)
              sl_DhVertex = vec4(rel, 1.0);
              uint rgb = r.w & 0xFFFFu;
              sl_DhColor = vec4(float((rgb >> 11) & 31u) / 31.0, float((rgb >> 5) & 63u) / 63.0, float(rgb & 31u) / 31.0, 1.0);
              sl_DhNormal = face == 0u ? vec3(0.0, 1.0, 0.0) : face == 5u ? vec3(0.0, -1.0, 0.0)
                  : face == 1u ? vec3(1.0, 0.0, 0.0) : face == 2u ? vec3(-1.0, 0.0, 0.0)
                  : face == 3u ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 0.0, -1.0);
              sl_DhLight = vec4(0.0, float((r.w >> 16) & 15u) * 16.0, 0.0, 1.0);
              sl_DhMaterial = int((r.z >> 27) & 15u);
            }
            """;
    Map<String, String> expressions = new LinkedHashMap<>();
    expressions.put("gl_Vertex", "sl_DhVertex");
    expressions.put("gl_Color", "sl_DhColor");
    expressions.put("gl_Normal", "sl_DhNormal");
    expressions.put("gl_MultiTexCoord0", "vec4(0.0, 0.0, 0.0, 1.0)");
    expressions.put("gl_MultiTexCoord1", "sl_DhLight");
    expressions.put("dhMaterialId", "sl_DhMaterial");
    expressions.put("gl_ModelViewMatrix", "gbufferModelView");
    expressions.put("gl_ProjectionMatrix", "dhProjection");
    expressions.put("gl_ModelViewProjectionMatrix", "(dhProjection * gbufferModelView)");
    expressions.put("gl_NormalMatrix", "transpose(mat3(gbufferModelViewInverse))");
    expressions.put("gl_TextureMatrix", "sl_PackTextureMatrices");
    expressions.put("mc_Entity", "vec4(-1.0, -1.0, 0.0, 1.0)");
    expressions.put("mc_midTexCoord", "vec4(0.0, 0.0, 0.0, 1.0)");
    Map<String, UniformLayout.Declaration> uniforms = new LinkedHashMap<>();
    for (String name : List.of("gbufferModelView", "gbufferModelViewInverse", "dhProjection"))
      uniforms.put(name, new UniformLayout.Declaration("mat4", 0));
    String initialization =
        "for (int i = 0; i < 8; ++i) sl_PackTextureMatrices[i] = mat4(1.0);\n"
            + "sl_PackTextureMatrices[1][0][0] = 1.0 / 256.0;\n"
            + "sl_PackTextureMatrices[1][1][1] = 1.0 / 256.0;\n"
            + "sl_PackTextureMatrices[1][3].xy = vec2(8.0 / 256.0);\n"
            + "sl_DhDecode();\n";
    return new ShaderCompatibilityCompiler.VertexAdapter(declarations, initialization, expressions, List.of(), uniforms);
  }

  public static ShaderCompatibilityCompiler.VertexAdapter adapter(
      RenderPipeline pipeline, boolean shadow) {
    return adapter(pipeline, shadow, false);
  }

  public static boolean isWaterMask(RenderPipeline pipeline) {
    return pipeline
        .getShaders()
        .get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX)
        .getPath()
        .equals("core/rendertype_water_mask");
  }

  /** Retain the pack's geometry transforms while preserving the boat mask's depth-only contract. */
  public static TranslatedProgram waterMaskProgram(TranslatedProgram geometry) {
    return new TranslatedProgram(
        geometry.label() + "/water_mask",
        geometry.vertexSource(),
        "#version 450\nvoid main() {}\n",
        geometry.uniforms(),
        geometry.samplers(),
        geometry.attributes(),
        List.of(),
        geometry.preprocessedVertex(),
        geometry.preprocessedFragment(),
            geometry.storage());
  }

  public static ShaderCompatibilityCompiler.VertexAdapter adapter(
      RenderPipeline pipeline, boolean shadow, boolean hand) {
    return adapter(
        pipeline, shadow, hand, Boolean.getBoolean("cadence.shaders.terrainFrameMatrices"));
  }

  /** Explicit mode is useful for GPU A/B validation without changing process-wide properties. */
  public static ShaderCompatibilityCompiler.VertexAdapter adapter(
      RenderPipeline pipeline, boolean shadow, boolean hand, boolean terrainFrameMatrices) {
    boolean terrain = bindings(pipeline).contains("TerrainUniform");
    String vertexId =
        pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX).getPath();
    boolean clouds = vertexId.equals("core/clouds");
    boolean lines = vertexId.equals("core/rendertype_lines");
    boolean border = vertexId.equals("core/world_border");
    boolean portal = vertexId.equals("core/rendertype_end_portal");
    boolean leash = vertexId.equals("core/rendertype_leash");
    boolean waterMask = vertexId.equals("core/rendertype_water_mask");
    boolean frameTerrain =
        terrainFrameMatrices && terrain && vertexId.equals("core/terrain") && !hand;
    boolean screenOverlay =
        vertexId.equals("core/position_tex_color")
            && (pipeline.getLocation().getPath().endsWith("/block_screen_effect")
                || pipeline.getLocation().getPath().endsWith("/fire_screen_effect"));
    boolean untextured =
        lines
            || vertexId.equals("core/position_color")
            || vertexId.equals("core/debug_point")
            || vertexId.equals("core/rendertype_lightning")
            || vertexId.equals("core/rendertype_outline")
            || leash
            || waterMask;
    StringBuilder declarations = new StringBuilder();
    Set<String> names = new LinkedHashSet<>();
    boolean packedMidUv = false;
    List<TranslatedProgram.Attribute> attributes = new ArrayList<>();
    int location = 0;
    for (VertexFormat supplied : pipeline.getVertexFormatBindings()) {
      if (supplied == null) continue;
      VertexFormat format =
          terrain
                  && supplied.contains("Position")
                  && TerrainShaderGeometry.layoutFor(supplied) == null
              ? TerrainShaderGeometry.layout().format()
              : supplied;
      for (var element : format.getElements()) {
        String type = glslType(element.format());
        if (element.name().equals("PackMidUV") && type.startsWith("u")) packedMidUv = true;
        if (!names.add(element.name()))
          throw new IllegalArgumentException("Duplicate Minecraft vertex input " + element.name());
        declarations
            .append("layout(location=")
            .append(location)
            .append(") in ")
            .append(type)
            .append(' ')
            .append(element.name())
            .append(";\n");
        attributes.add(
            new TranslatedProgram.Attribute(element.name(), element.name(), type, location++));
      }
    }
    if (!names.contains("Position") && !clouds)
      throw new UnsupportedOperationException(
          "Shader-pack geometry needs an explicit position adapter for " + pipeline.getLocation());
    Set<String> uniformBindings = bindings(pipeline);
    if (terrain) {
      declarations.append(
          """
          layout(std140) uniform TerrainUniform { mat4 ModelViewMat; ivec2 TextureSize; };
          layout(std140) uniform Globals {
            ivec3 CameraBlockPos; float GlintAlpha; vec3 CameraOffset; float GameTime;
            vec2 ScreenSize; int MenuBlurRadius; int UseRgss;
          };
          """);
      if (!names.contains("ChunkPosition")) {
        if (!uniformBindings.contains("ChunkSection"))
          throw new UnsupportedOperationException(
              "Terrain pipeline has no chunk position: " + pipeline.getLocation());
        declarations.append(
            "layout(std140) uniform ChunkSection { ivec3 ChunkPosition; float ChunkVisibility;"
                + " };\n");
      }
    } else {
      if (!uniformBindings.contains("DynamicTransforms"))
        throw new UnsupportedOperationException(
            "Shader-pack geometry has no model-view transform: " + pipeline.getLocation());
      declarations.append(
          """
          layout(std140) uniform DynamicTransforms {
            mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset;
          };
          """);
    }
    declarations.append(
        """
        layout(std140) uniform Projection { mat4 ProjMat; };
        mat4 sl_PackTextureMatrices[8];
        mat4 sl_ToOpenGLProjection(mat4 original) {
          mat4 result = original;
          for (int column = 0; column < 4; ++column)
            result[column].z = original[column].w - 2.0 * original[column].z;
          return result;
        }
        mat4 sl_HandProjection(mat4 projection) {
          for (int column = 0; column < 4; ++column) projection[column].z *= 0.125;
          return projection;
        }
        """);
    if (clouds) declarations.append(CLOUD_DECLARATIONS);
    if (lines) declarations.append(LINE_DECLARATIONS);
    Map<String, String> expressions = new LinkedHashMap<>();
    String position =
        lines
            ? "sl_LinePosition"
            : clouds
                ? "sl_CloudPosition"
                : terrain
                    ? "Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset"
                    : "Position";
    // The vanilla block shader alone adds ModelOffset; entity poses already include their offset.
    if (!terrain && vertexId.equals("core/block")) position += " + ModelOffset";
    if (vertexId.equals("core/world_border")) position += " + ModelOffset";
    expressions.put("gl_Vertex", "vec4(" + position + ", 1.0)");
    // Minecraft 26.3 folds bob/hurt/nausea into its projection. Legacy packs reconstruct
    // depth using a symmetric projection, so frame uniforms move that camera effect into
    // model-view. Cancel the same effect from each actual draw projection: this retains
    // special sky/line projections and exactly preserves clip coordinates.
    // Hand poses already contain their own bob/hurt and use a separate HUD projection.
    String modelView =
        shadow
            ? "shadowModelView * gbufferModelViewInverse * sl_CameraEffect * ModelViewMat"
            : hand ? "ModelViewMat" : "sl_CameraEffect * ModelViewMat";
    String projection =
        shadow
            ? "shadowProjection"
            : hand
                ? "sl_ToOpenGLProjection(ProjMat)"
                : "sl_ToOpenGLProjection(ProjMat) * sl_CameraEffectInverse";
    Map<String, UniformLayout.Declaration> adapterUniforms = new LinkedHashMap<>();
    if (!frameTerrain && (shadow || !hand))
      adapterUniforms.put("sl_CameraEffect", new UniformLayout.Declaration("mat4", 0));
    if (!frameTerrain && !shadow && !hand)
      adapterUniforms.put("sl_CameraEffectInverse", new UniformLayout.Declaration("mat4", 0));
    if (!frameTerrain && shadow) {
      adapterUniforms.put("shadowModelView", new UniformLayout.Declaration("mat4", 0));
      adapterUniforms.put("shadowProjection", new UniformLayout.Declaration("mat4", 0));
      adapterUniforms.put("gbufferModelViewInverse", new UniformLayout.Declaration("mat4", 0));
    }
    String modelViewProjection = projection + " * " + modelView;
    String normalMatrix = "transpose(inverse(mat3(" + modelView + ")))";
    if (frameTerrain) {
      // MC 26.3 LevelRenderer passes raw camera V to both terrain preparation paths. Our
      // shadow replay does the same. Thus B*V is the already published gbuffer model-view,
      // while S*(B*V)^-1*B*V is just the shadow model-view. Only core terrain has this contract;
      // dynamic models, hand, sky and line draws retain their actual draw matrices above.
      modelView = shadow ? "shadowModelView" : "gbufferModelView";
      projection = shadow ? "shadowProjection" : "gbufferProjection";
      String inverse = shadow ? "shadowModelViewInverse" : "gbufferModelViewInverse";
      modelViewProjection =
          shadow ? "sl_ShadowModelViewProjection" : "sl_TerrainModelViewProjection";
      normalMatrix = "transpose(mat3(" + inverse + "))";
      for (String name : List.of(modelView, projection, inverse, modelViewProjection))
        adapterUniforms.put(name, new UniformLayout.Declaration("mat4", 0));
    }
    // The documented MC_HAND_DEPTH multiplier acts on clip-space Z, before depth conversion.
    if (hand && !shadow) {
      projection = "sl_HandProjection(" + projection + ")";
      modelViewProjection = projection + " * " + modelView;
    }
    expressions.put("gl_ModelViewMatrix", modelView);
    expressions.put("gl_ProjectionMatrix", projection);
    expressions.put("gl_ModelViewProjectionMatrix", modelViewProjection);
    expressions.put("gl_NormalMatrix", normalMatrix);
    expressions.put("gl_TextureMatrix", "sl_PackTextureMatrices");
    expressions.put(
        "gl_Color",
        clouds
            ? "sl_CloudColor"
            : names.contains("Color")
                ? terrain ? "Color" : "Color * ColorModulator"
                : terrain ? "vec4(1.0)" : "ColorModulator");
    if (names.contains("UV0")) expressions.put("gl_MultiTexCoord0", "vec4(UV0, 0.0, 1.0)");
    if (names.contains("UV2")) expressions.put("gl_MultiTexCoord1", "vec4(vec2(UV2), 0.0, 1.0)");
    if (vertexId.equals("core/text")
        && !names.contains("UV2")
        && (pipeline.getShaderDefines().flags().contains("IS_GUI")
            || pipeline.getShaderDefines().flags().contains("IS_SEE_THROUGH"))) {
      // Vanilla core/text.vsh omits lightmap sampling entirely in these variants.
      // Legacy packs still request its coordinate: encode both full-bright light channels,
      // rather than the dark current-attribute default. Normal world text retains its UV2.
      expressions.put("gl_MultiTexCoord1", "vec4(240.0, 240.0, 0.0, 1.0)");
    }
    if (screenOverlay && !names.contains("UV2")) {
      // Vanilla first-person block/fire overlays are unlit textured XY quads.
      expressions.put("gl_MultiTexCoord1", "vec4(240.0, 240.0, 0.0, 1.0)");
      expressions.put("gl_Normal", "vec3(0.0, 0.0, 1.0)");
    }
    if (untextured) {
      // These untextured draws have no texture coordinate arrays. Preserve OpenGL's
      // initial current texture coordinates when a pack shares a common vertex helper.
      if (!names.contains("UV0")) expressions.put("gl_MultiTexCoord0", "vec4(0.0, 0.0, 0.0, 1.0)");
      if (!names.contains("UV2")) expressions.put("gl_MultiTexCoord1", "vec4(0.0, 0.0, 0.0, 1.0)");
    }
    if (border || portal) {
      if (!names.contains("UV0")) expressions.put("gl_MultiTexCoord0", "vec4(0.0, 0.0, 0.0, 1.0)");
      if (!names.contains("UV2")) expressions.put("gl_MultiTexCoord1", "vec4(0.0, 0.0, 0.0, 1.0)");
    }
    if (names.contains("Normal")) expressions.put("gl_Normal", "Normal.xyz");
    else if (vertexId.equals("core/particle")) {
      // Particle quads face the camera. Transform their view-space face normal back into
      // the coordinate space consumed by the pack's normal matrix, including camera roll.
      expressions.put(
          "gl_Normal", "normalize(transpose(mat3(ModelViewMat)) * vec3(0.0, 0.0, 1.0))");
    } else if (vertexId.equals("core/text")) {
      // Glyphs are planar quads in the text renderer's local XY plane.
      expressions.put("gl_Normal", "vec3(0.0, 0.0, 1.0)");
    }
    if (untextured) {
      // These legacy geometry categories have no surface-normal stream. The line Normal
      // stream is a segment direction used for extrusion. Leashes retain their actual
      // per-vertex lightmap above, and use the fixed-function current-normal initial value.
      expressions.put("gl_Normal", "vec3(0.0, 0.0, 1.0)");
    }
    if (border) {
      // The border VBO contains four fixed quads in SOUTH, WEST, NORTH, EAST order.
      // Their indexed vertex IDs retain that order even when distant sides are omitted.
      expressions.put(
          "gl_Normal",
          "vec3[4](vec3(0,0,1), vec3(-1,0,0), vec3(0,0,-1), vec3(1,0,0))[(gl_VertexIndex / 4) &"
              + " 3]");
    }
    if (clouds) {
      expressions.put("gl_Normal", "sl_CloudNormal");
      // 26.3 cloud faces already encode the cloud-mask coverage in their geometry. Their
      // material is a solid color, and the runtime binds the matching white albedo texture.
      expressions.put("gl_MultiTexCoord0", "vec4(0.0, 0.0, 0.0, 1.0)");
    }
    expressions.put(
        "mc_Entity",
        names.contains("PackMetadata")
            ? "vec4(float(int(PackMetadata << 16u) >> 16), float((PackMetadata >> 16u) & 1u) * 2.0"
                  + " - 1.0, 0.0, 1.0)"
            : names.contains("PackEntity")
                ? "vec4(PackEntity, 0.0, 1.0)"
                : "vec4(-1.0, -1.0, 0.0, 1.0)");
    if (names.contains("PackMidUV"))
      expressions.put(
          "mc_midTexCoord",
          packedMidUv ? "vec4(vec2(PackMidUV) * (1.0 / 65536.0), 0.0, 1.0)" : "vec4(PackMidUV, 0.0, 1.0)");
    else if (shadow
        && (vertexId.equals("core/entity")
            || vertexId.equals("core/item")
            || vertexId.equals("core/block")
            || portal
            || leash)) {
      // Dynamic model shadows have no block-material ID or per-block UV midpoint. Supply
      // the OpenGL generic-attribute initial value for that absent stream. BSL's vegetation
      // displacement cannot select it when mc_Entity is unmapped; terrain keeps its real data.
      // https://wikis.khronos.org/opengl/Vertex_Specification#Non-array_attribute_values
      expressions.put("mc_midTexCoord", "vec4(0.0, 0.0, 0.0, 1.0)");
    }
    if (names.contains("PackTangent")) expressions.put("at_tangent", "PackTangent");
    if (names.contains("PackMidBlock")) expressions.put("at_midBlock", "PackMidBlock");
    else if (names.contains("PackMetadata")) {
      // The emitting block's center is independent of Position: mod geometry may extend outside
      // its block. Keep float subtraction before multiplication, matching the full CPU record.
      expressions.put(
          "at_midBlock",
          "((vec3((PackMetadata >> 17u) & 15u, (PackMetadata >> 21u) & 15u, (PackMetadata >> 25u) &"
              + " 15u) + 0.5 - Position) * 64.0)");
    }
    String initialization =
        "for (int i = 0; i < 8; ++i) sl_PackTextureMatrices[i] = mat4(1.0);\n"
            + "sl_PackTextureMatrices[1][0][0] = 1.0 / 256.0;\n"
            + "sl_PackTextureMatrices[1][1][1] = 1.0 / 256.0;\n"
            + "sl_PackTextureMatrices[1][3].xy = vec2(8.0 / 256.0);\n";
    if (!terrain
        && (pipeline.getShaderDefines().flags().contains("APPLY_TEXTURE_MATRIX")
            || vertexId.equals("core/glint")))
      initialization += "sl_PackTextureMatrices[0] = TextureMat;\n";
    if (vertexId.equals("core/world_border"))
      initialization += "sl_PackTextureMatrices[0] = TextureMat;\n";
    if (clouds) initialization += "sl_DecodeCloudFace();\n";
    if (lines) initialization += "sl_ExpandLine();\n";
    if (vertexId.equals("core/debug_point")) initialization += "gl_PointSize = LineWidth;\n";
    return new ShaderCompatibilityCompiler.VertexAdapter(
        declarations.toString(), initialization, expressions, attributes, adapterUniforms);
  }

  /** Uniform blocks which the adapted shader actually declares, retaining vanilla names/layouts. */
  public static List<String> uniformBlocks(RenderPipeline pipeline) {
    if (pipeline
        .getShaders()
        .get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX)
        .getPath()
        .equals("core/rendertype_lines"))
      return List.of("DynamicTransforms", "Projection", "Globals");
    if (bindings(pipeline).contains("CloudInfo"))
      return List.of("DynamicTransforms", "Projection", "CloudInfo");
    if (!bindings(pipeline).contains("TerrainUniform"))
      return List.of("DynamicTransforms", "Projection");
    boolean indirect =
        pipeline.getVertexFormatBindings().stream()
            .filter(java.util.Objects::nonNull)
            .anyMatch(format -> format.contains("ChunkPosition"));
    return indirect
        ? List.of("TerrainUniform", "Globals", "Projection")
        : List.of("TerrainUniform", "Globals", "ChunkSection", "Projection");
  }

  private static Set<String> bindings(RenderPipeline pipeline) {
    Set<String> result = new LinkedHashSet<>();
    for (var binding : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()))
      if (binding.type() == UniformType.UNIFORM_BUFFER) result.add(binding.name());
    return result;
  }

  private static String glslType(GpuFormat format) {
    String name = format.name();
    String prefix = name.endsWith("_SINT") ? "i" : name.endsWith("_UINT") ? "u" : "";
    int components = format.componentCount();
    if (components == 1) return prefix.isEmpty() ? "float" : prefix.equals("i") ? "int" : "uint";
    return prefix + "vec" + components;
  }

  // Minecraft 26.3 publishes the compressed cloud-face contract in core/clouds.vsh. These
  // values decode the same face buffer, so no cloud geometry is read back or rebuilt on CPU.
  private static final String CLOUD_DECLARATIONS =
      """
      layout(std140) uniform CloudInfo { vec4 CloudColor; vec3 CloudOffset; vec3 CellSize; };
      uniform isamplerBuffer CloudFaces;
      vec3 sl_CloudPosition;
      vec3 sl_CloudNormal;
      vec4 sl_CloudColor;
      void sl_DecodeCloudFace() {
        int face = (gl_VertexIndex / 4) * 3;
        ivec2 cell = ivec2(texelFetch(CloudFaces, face).r, texelFetch(CloudFaces, face + 1).r);
        int flags = texelFetch(CloudFaces, face + 2).r;
        int direction = flags & 7;
        bool inside = (flags & 16) != 0;
        cell = cell * 2 + ivec2((flags >> 7) & 1, (flags >> 6) & 1);
        int corner = gl_VertexIndex & 3;
        int bit = direction * 4 + (inside ? 3 - corner : corner);
        vec3 unit = vec3((0xF03CC3 >> bit) & 1, (0x6666F0 >> bit) & 1, (0xC3F066 >> bit) & 1);
        sl_CloudPosition = (unit + vec3(cell.x, 0, cell.y)) * CellSize + CloudOffset;
        const vec3 normals[6] = vec3[6](vec3(0,-1,0),vec3(0,1,0),vec3(0,0,-1),
            vec3(0,0,1),vec3(-1,0,0),vec3(1,0,0));
        sl_CloudNormal = normals[direction] * (inside ? -1.0 : 1.0);
        const float brightness[6] = float[6](0.7,1.0,0.8,0.8,0.9,0.9);
        float shade = (flags & 32) != 0 ? 1.0 : brightness[direction];
        sl_CloudColor = CloudColor * vec4(vec3(shade), 1.0);
      }
      """;

  // Vanilla lines are duplicated vertex pairs expanded in screen space, not line-list
  // geometry. Recover the expanded model position before the pack applies its projection
  // and jitter, so outlines retain their real pixel width and view-shrink depth offset.
  private static final String LINE_DECLARATIONS =
      """
      layout(std140) uniform Globals {
        ivec3 CameraBlockPos; float GlintAlpha; vec3 CameraOffset; float GameTime;
        vec2 ScreenSize; int MenuBlurRadius; int UseRgss;
      };
      vec3 sl_LinePosition;
      void sl_ExpandLine() {
        vec4 viewStart = ModelViewMat * vec4(Position, 1.0);
        vec4 viewEnd = ModelViewMat * vec4(Position + Normal.xyz, 1.0);
        viewStart.xyz *= 255.0 / 256.0;
        viewEnd.xyz *= 255.0 / 256.0;
        vec4 start = ProjMat * viewStart;
        vec4 end = ProjMat * viewEnd;
        vec2 direction = (end.xy / end.w - start.xy / start.w) * ScreenSize;
        direction /= max(length(direction), 0.000001);
        vec2 offset = vec2(-direction.y, direction.x) * LineWidth / ScreenSize;
        if (offset.x < 0.0) offset *= -1.0;
        start.xy += offset * start.w * ((gl_VertexIndex & 1) == 0 ? 1.0 : -1.0);
        vec4 model = inverse(ModelViewMat) * inverse(ProjMat) * start;
        sl_LinePosition = model.xyz / model.w;
      }
      """;
}
