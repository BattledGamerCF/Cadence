// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.compile;

import static org.lwjgl.util.shaderc.Shaderc.*;

import dev.cadence.shaders.pack.AlphaTestPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowers the legacy GLSL interface used by OptiFine-format packs to RenderPearl GLSL. Conditional
 * compilation precedes interface extraction: disabled effects cannot create bindings, and
 * unsupported active features produce an error rather than silently changing the pack.
 */
public final class ShaderCompatibilityCompiler implements AutoCloseable {
  // Cadence: horizontal whitespace only before the directive. With \s (which matches newlines too) every line start in a
  // run of k blank lines (shaderc's output is full of them, where #ifdef blocks were) scanned the rest of the run and
  // backtracked: O(k^2), seconds of render-thread time per pack load. Same GLSL; only blank lines are no longer removed.
  private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t\\x0B\\f]*#version[^\\r\\n]*");
  private static final Pattern LINE = Pattern.compile("(?m)^[ \\t\\x0B\\f]*#line[^\\r\\n]*");
  private static final Pattern EXTENSION = Pattern.compile("(?m)^[ \\t\\x0B\\f]*#extension[^\\r\\n]*");
  private static final Pattern DRAW =
      Pattern.compile("/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9,Nn ]+)\\s*\\*/");
  private static final Pattern SENTINEL =
      Pattern.compile("const\\s+int\\s+sl_directive_(\\d+)\\s*=\\s*0\\s*;");
  private static final Pattern DECLARATION =
      Pattern.compile(
          "\\b((?:(?:flat|smooth|noperspective|centroid|sample)\\s+)*)(uniform|varying|attribute)\\s+(?:(lowp|mediump|highp)\\s+)?(\\w+)\\s+([^;{}]+);");
  private static final Pattern NAME = Pattern.compile("([A-Za-z_]\\w*)\\s*(?:\\[\\s*(\\d+)\\s*])?");
  private static final Pattern OUTPUT = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*]");
  private static final boolean EVALUATE_CONDITIONALS =
      !"false".equals(System.getProperty("cadence.shaders.evaluateConditionals"));
  private static final Pattern REDEFINE =
      Pattern.compile("(?m)^([ \\t]*#[ \\t]*define[ \\t]+([A-Za-z_]\\w*))");
  private static final Pattern FOG_FIELD =
      Pattern.compile("\\bgl_Fog\\s*\\.\\s*(color|density|start|end|scale)\\b");
  /** -Dcadence.shaders.conservativeDepth=false: world programs that write gl_FragDepth may move it either way (no early-Z). */
  private static final boolean CONSERVATIVE_DEPTH =
      !"false".equals(System.getProperty("cadence.shaders.conservativeDepth"));
  private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
  // Valid legacy GLSL identifiers which are C++/Metal keywords. SPIRV-Cross does not escape all
  // of these (BSL's deferred shader uses `new`), so preserve them under a private prefix.
  // Cadence: also `sampler`, a type name in Vulkan GLSL but a plain identifier in OpenGL's (Bliss names a parameter so).
  private static final Set<String> METAL_KEYWORDS =
      Set.of(
          "sampler",
          // Cadence: names in Metal's namespace that are ambiguous as a variable (Bliss names one `bias`), and Metal
          // functions SPIRV-Cross calls, which a pack variable of that name hides (Pegasus: `vec2 dfdx = dFdx(...)`)
          "bias",
          "dfdx",
          "dfdy",
          "saturate",
          "select",
          "rint",
          "fmin",
          "fmax",
          "rsqrt",
          "popcount",
          "clz",
          "ctz",
          "median3",
          "min3",
          "max3",
          // Cadence: Metal math functions GLSL lacks, so a pack's own function of that name is called ambiguously in Metal
          // (Photon: length_squared; Noble: log10)
          "length_squared",
          "distance_squared",
          "log10",
          "exp10",
          "powr",
          "sincos",
          "copysign",
          "fdim",
          "sinpi",
          "cospi",
          "tanpi",
          "level",
          "gradient2d",
          "gradient3d",
          "gradientcube",
          "min_lod_clamp",
          "access",
          "new",
          "delete",
          "operator",
          "template",
          "class",
          "namespace",
          "using",
          "this",
          "alignas",
          "alignof",
          "catch",
          "decltype",
          "explicit",
          "friend",
          "mutable",
          "noexcept",
          "nullptr",
          "private",
          "protected",
          "public",
          "reinterpret_cast",
          "static_assert",
          "thread_local",
          "throw",
          "try",
          "typeid",
          "typename",
          "virtual",
          "wchar_t");
  private static final Map<String, String> BUILTIN_UNIFORMS =
      Map.of(
          "gl_ModelViewMatrix",
          "mat4",
          "gl_ProjectionMatrix",
          "mat4",
          "gl_ModelViewProjectionMatrix",
          "mat4",
          "gl_NormalMatrix",
          "mat3",
          "gl_TextureMatrix",
          "mat4");
  private static final List<TranslatedProgram.Attribute> BUILTIN_ATTRIBUTES =
      List.of(
          new TranslatedProgram.Attribute("gl_Vertex", "sl_Vertex", "vec4", 0),
          new TranslatedProgram.Attribute("gl_Color", "sl_Color", "vec4", 1),
          new TranslatedProgram.Attribute("gl_MultiTexCoord0", "sl_MultiTexCoord0", "vec4", 2),
          new TranslatedProgram.Attribute("gl_MultiTexCoord1", "sl_MultiTexCoord1", "vec4", 3),
          new TranslatedProgram.Attribute("gl_Normal", "sl_Normal", "vec3", 4));
  private long compiler = shaderc_compiler_initialize();
  private final ShadowComparison shadowComparison;

  /** Hardware mode requires a linear, clamp-to-edge LEQUAL comparison sampler at runtime. */
  public enum ShadowComparison {
    EMULATED,
    HARDWARE
  }

  public enum VertexMode {
    GEOMETRY,
    FULLSCREEN
  }

  /**
   * An explicit bridge from a Minecraft vertex format and vanilla draw uniforms to pack inputs. fragmentValues: legacy
   * expressions the fragment stage may read too (name -> GLSL type), passed as flat varyings; functions: definitions either
   * stage gets when it calls one of them without defining it (name -> GLSL definition).
   */
  public record VertexAdapter(
      String declarations,
      String initialization,
      Map<String, String> legacyExpressions,
      List<TranslatedProgram.Attribute> attributes,
      Map<String, UniformLayout.Declaration> uniforms,
      Map<String, String> fragmentValues,
      Map<String, String> functions) {
    public VertexAdapter(
        String declarations,
        String initialization,
        Map<String, String> legacyExpressions,
        List<TranslatedProgram.Attribute> attributes) {
      this(declarations, initialization, legacyExpressions, attributes, Map.of());
    }

    public VertexAdapter(
        String declarations,
        String initialization,
        Map<String, String> legacyExpressions,
        List<TranslatedProgram.Attribute> attributes,
        Map<String, UniformLayout.Declaration> uniforms) {
      this(declarations, initialization, legacyExpressions, attributes, uniforms, Map.of(), Map.of());
    }

    public VertexAdapter {
      legacyExpressions = Map.copyOf(legacyExpressions);
      attributes = List.copyOf(attributes);
      uniforms = Map.copyOf(uniforms);
      fragmentValues = Map.copyOf(fragmentValues);
      functions = Map.copyOf(functions);
    }
  }

  public ShaderCompatibilityCompiler() {
    this(ShadowComparison.EMULATED);
  }

  public ShaderCompatibilityCompiler(ShadowComparison shadowComparison) {
    this.shadowComparison = java.util.Objects.requireNonNull(shadowComparison);
    if (compiler == 0) throw new IllegalStateException("Cannot initialize shader preprocessor");
  }

  public TranslatedProgram translate(String vertex, String fragment, String label) {
    return translate(vertex, fragment, label, VertexMode.GEOMETRY);
  }

  public synchronized TranslatedProgram translate(
      String vertex, String fragment, String label, VertexMode mode) {
    return translate(vertex, fragment, label, mode, null, AlphaTestPolicy.OFF);
  }

  public TranslatedProgram translate(
      String vertex, String fragment, String label, VertexAdapter adapter) {
    return translate(vertex, fragment, label, VertexMode.GEOMETRY, adapter, AlphaTestPolicy.OFF);
  }

  public TranslatedProgram translate(
      String vertex,
      String fragment,
      String label,
      VertexAdapter adapter,
      AlphaTestPolicy alphaTest) {
    return translate(vertex, fragment, label, VertexMode.GEOMETRY, adapter, alphaTest);
  }

  public TranslatedProgram translate(
      String vertex,
      String fragment,
      String label,
      VertexAdapter adapter,
      AlphaTestPolicy alphaTest,
      boolean earlyAlphaDemote) {
    return translate(
        vertex, fragment, label, VertexMode.GEOMETRY, adapter, alphaTest, earlyAlphaDemote);
  }

  public TranslatedProgram translate(
      String vertex, String fragment, String label, VertexMode mode, AlphaTestPolicy alphaTest) {
    return translate(vertex, fragment, label, mode, null, alphaTest);
  }

  private synchronized TranslatedProgram translate(
      String vertex,
      String fragment,
      String label,
      VertexMode mode,
      VertexAdapter adapter,
      AlphaTestPolicy alphaTest) {
    return translate(vertex, fragment, label, mode, adapter, alphaTest, false);
  }

  private synchronized TranslatedProgram translate(
      String vertex,
      String fragment,
      String label,
      VertexMode mode,
      VertexAdapter adapter,
      AlphaTestPolicy alphaTest,
      boolean earlyAlphaDemote) {
    if (compiler == 0) throw new IllegalStateException("Shader compiler is closed");
    // Cadence: translated once per distinct input, then from memory or disk (TranslationCache)
    Object[] key = {
      vertex, fragment, label, mode, alphaTest, earlyAlphaDemote, shadowComparison, storageContext,
      adapter == null ? "" : adapter.declarations(),
      adapter == null ? "" : adapter.initialization(),
      adapter == null ? "" : new java.util.TreeMap<>(adapter.legacyExpressions()),
      adapter == null ? "" : adapter.attributes(),
      adapter == null ? "" : new java.util.TreeMap<>(adapter.uniforms()),
      adapter == null ? "" : new java.util.TreeMap<>(adapter.fragmentValues()),
      adapter == null ? "" : new java.util.TreeMap<>(adapter.functions())
    };
    return TranslationCache.get(
        key, () -> translateNow(vertex, fragment, label, mode, adapter, alphaTest, earlyAlphaDemote));
  }

  private TranslatedProgram translateNow(
      String vertex,
      String fragment,
      String label,
      VertexMode mode,
      VertexAdapter adapter,
      AlphaTestPolicy alphaTest,
      boolean earlyAlphaDemote) {
    if (mode == VertexMode.FULLSCREEN && alphaTest.enabled())
      throw unsupported(label, "host alpha testing on a screen pass");
    uniformDefaults = new LinkedHashMap<>();
    zeroInputs = new LinkedHashSet<>();
    vertexUniforms = new LinkedHashSet<>();
    fragmentUniforms = new LinkedHashSet<>();
    Preprocessed v = preprocess(vertex, shaderc_vertex_shader, label + ".vsh");
    Preprocessed f = preprocess(fragment, shaderc_fragment_shader, label + ".fsh");
    Map<String, UniformLayout.Declaration> uniforms = new LinkedHashMap<>();
    Map<String, String> samplers = new LinkedHashMap<>();
    Map<String, UniformLayout.Declaration> varyings = new LinkedHashMap<>();
    Map<String, String> varyingQualifiers = new LinkedHashMap<>();
    List<TranslatedProgram.Attribute> attributes = new ArrayList<>();
    // Cadence: GLSL 1.30+ in/out declarations (Complementary, I Like Vanilla) become the legacy forms below
    List<String[]> modernOutputs = new ArrayList<>();
    Map<Integer, String> integerOutputs = new HashMap<>();  // (location -> uvec4 / ivec4)
    // Cadence: images, buffer objects and set-1 samplers out first (StorageScan): one binding per name for both stages
    StorageScan storage = new StorageScan();
    // (an adapter's own buffer objects, Cadence's far terrain for dh_* programs: set-1 resources like a pack's)
    String adapterDeclarations = adapter == null ? "" : storage.extract(adapter.declarations());
    String vertexBody =
        declarations(
            modernInOut(storage.extract(arraysOnNames(v.source())), true, null), true, uniforms, samplers, varyings, varyingQualifiers,
            attributes);
    String fragmentBody =
        declarations(
            modernInOut(storage.extract(arraysOnNames(f.source())), false, modernOutputs), false, uniforms, samplers, varyings,
            varyingQualifiers, attributes);
    // Cadence: OpenGL's fixed-function fog. gl_Fog (the fog parameters) becomes uniforms the game fills from its own fog;
    // gl_FogFragCoord (the fog distance the vertex stage writes and the fragment stage reads) becomes a plain varying.
    Matcher fog = FOG_FIELD.matcher(vertexBody + "\n" + fragmentBody);
    while (fog.find())
      merge(uniforms, "sl_Fog_" + fog.group(1),
          new UniformLayout.Declaration(fog.group(1).equals("color") ? "vec4" : "float", 0));
    vertexBody = FOG_FIELD.matcher(vertexBody).replaceAll("sl_Fog_$1");
    fragmentBody = FOG_FIELD.matcher(fragmentBody).replaceAll("sl_Fog_$1");
    if (token(vertexBody, "gl_FogFragCoord") || token(fragmentBody, "gl_FogFragCoord")) {
      merge(varyings, "sl_FogFragCoord", new UniformLayout.Declaration("float", 0));
      vertexBody = replaceToken(vertexBody, "gl_FogFragCoord", "sl_FogFragCoord");
      fragmentBody = replaceToken(fragmentBody, "gl_FogFragCoord", "sl_FogFragCoord");
    }
    // Cadence: Iris's core-profile inputs, which Iris gives every program whether declared or not (Complementary's and
    // I Like Vanilla's gbuffers_line use them), as the legacy built-ins they equal.
    for (var core : IRIS_CORE.entrySet()) {
      // a pack's own variable of that name (a local `mat3 normalMatrix = ...`) is left alone
      Pattern own = Pattern.compile("\\b(?:float|int|bool|[biu]?vec[234]|mat[234](?:x[234])?)\\s+" + core.getKey() + "\\b");
      if (own.matcher(vertexBody).find() || own.matcher(fragmentBody).find()) continue;
      vertexBody = replaceToken(vertexBody, core.getKey(), "(" + core.getValue() + ")");
      fragmentBody = replaceToken(fragmentBody, core.getKey(), "(" + core.getValue() + ")");
    }
    // Cadence: a pack written for GLSL 1.10/1.20 may define functions that later versions made built-in (Bliss defines
    // fma and tanh); in our #version 450 that is a redefinition. The pack's own functions are renamed, calls included.
    for (String builtin : LATER_BUILTINS) {
      Pattern defined =
          Pattern.compile("\\b(?:float|int|uint|bool|[biu]?vec[234]|mat[234])\\s+" + builtin + "\\s*\\([^)]*\\)\\s*\\{");
      if (!defined.matcher(vertexBody).find() && !defined.matcher(fragmentBody).find()) continue;
      Pattern call = Pattern.compile("\\b" + builtin + "(\\s*\\()");
      vertexBody = call.matcher(vertexBody).replaceAll("sl_pack_" + builtin + "$1");
      fragmentBody = call.matcher(fragmentBody).replaceAll("sl_pack_" + builtin + "$1");
    }
    // ftransform() is a legacy fixed-function matrix transform, not a backend operation.
    vertexBody =
        vertexBody.replaceAll(
            "\\bftransform\\s*\\(\\s*\\)", "(gl_ModelViewProjectionMatrix * gl_Vertex)");
    StringBuilder adapterCopyOut = new StringBuilder();
    if (adapter != null) {
      // Cadence: the game's vertex inputs keep their own names (Position, Color, UV0, UV2, Normal); a pack identifier of the
      // same name (Mellow's `Normal`) is renamed in both stages, or the two would be redefinitions of each other
      Matcher inputs = Pattern.compile("\\bin\\s+\\w+\\s+(\\w+)\\s*;").matcher(adapter.declarations());
      while (inputs.find()) {
        String input = inputs.group(1);
        if (token(vertexBody, input) || token(fragmentBody, input) || varyings.containsKey(input) || uniforms.containsKey(input)) {
          String renamed = "sl_pack_" + input;
          vertexBody = replaceToken(vertexBody, input, renamed);
          fragmentBody = replaceToken(fragmentBody, input, renamed);
          // (its declaration was already taken out of the code: a varying or uniform keeps its entry under the new name)
          if (varyings.containsKey(input)) varyings.put(renamed, varyings.remove(input));
          if (varyingQualifiers.containsKey(input)) varyingQualifiers.put(renamed, varyingQualifiers.remove(input));
          for (String[] out : modernOutputs) if (out[2].equals(input)) out[2] = renamed;  // (Mellow's `out vec4 Color`)
          if (uniforms.containsKey(input)) {
            uniforms.put(renamed, uniforms.remove(input));
            if (vertexUniforms.remove(input)) vertexUniforms.add(renamed);
            if (fragmentUniforms.remove(input)) fragmentUniforms.add(renamed);
          }
        }
      }
      adapter.uniforms().forEach((name, declaration) -> merge(uniforms, name, declaration));
      // (the adapter's expressions use them in the vertex stage, whichever stage the pack declares them in: Mellow declares
      // dhProjection in its fragment stage only, which hid it from the vertex stage)
      vertexUniforms.addAll(adapter.uniforms().keySet());
      // Cadence: an adapter value the fragment stage reads too (VanillaEX reads dhMaterialId there): a flat varying
      // the vertex stage fills from the adapter's expression
      for (var value : adapter.fragmentValues().entrySet()) {
        String name = value.getKey(), expression = adapter.legacyExpressions().get(name);
        if (expression == null || !token(fragmentBody, name) || varyings.containsKey(name) || uniforms.containsKey(name)) continue;
        String varying = "sl_adapter_" + name;
        fragmentBody = replaceToken(fragmentBody, name, varying);
        varyings.put(varying, new UniformLayout.Declaration(value.getValue(), 0));
        varyingQualifiers.put(varying, "flat");
        adapterCopyOut.append(varying).append(" = ").append(expression).append(";\n");
      }
      // Cadence: functions of the adapter's contract a pack calls without defining them (Noble's dh_hasTexture and
      // dh_sampleTexture)
      for (var function : adapter.functions().entrySet()) {
        Pattern defined = Pattern.compile("\\b\\w+\\s+" + Pattern.quote(function.getKey()) + "\\s*\\([^)]*\\)\\s*\\{");
        if (token(vertexBody, function.getKey()) && !defined.matcher(vertexBody).find()) vertexBody = function.getValue() + "\n" + vertexBody;
        if (token(fragmentBody, function.getKey()) && !defined.matcher(fragmentBody).find()) fragmentBody = function.getValue() + "\n" + fragmentBody;
      }
      // A single token pass prevents replacement expressions from being recursively rewritten.
      // Cadence: a pack's own declared type for an input (I Like Vanilla declares mc_midTexCoord a vec2; the adapter
      // supplies a vec4): converted as OpenGL converts a vertex attribute to the declared size
      Map<String, String> declared = new java.util.HashMap<>();
      for (var attribute : attributes) declared.put(attribute.legacyName(), attribute.type());
      Map<String, String> supplied = new java.util.HashMap<>();
      for (var attribute : adapter.attributes()) supplied.put(attribute.shaderName(), attribute.type());
      Matcher identifiers = Pattern.compile("\\b[A-Za-z_]\\w*\\b").matcher(vertexBody);
      StringBuffer adapted = new StringBuffer();
      while (identifiers.find()) {
        String expression = adapter.legacyExpressions().get(identifiers.group());
        if (expression != null) {
          String from = expressionType(identifiers.group(), expression, supplied);
          String to = declared.get(identifiers.group());
          String converted = from == null || to == null ? "(" + expression + ")" : convert("(" + expression + ")", from, to);
          identifiers.appendReplacement(adapted, Matcher.quoteReplacement(converted));
        }
      }
      identifiers.appendTail(adapted);
      vertexBody = adapted.toString();
      for (var attribute : attributes) {
        // Cadence: an input this draw's vertex format doesn't have (mc_midTexCoord on a held item or a particle) gets
        // Iris's value for it: mc_Entity -1 (not a block), a tangent along +X, otherwise zero.
        if (token(vertexBody, attribute.legacyName())
            && !adapter.legacyExpressions().containsKey(attribute.legacyName()))
          zeroInputs.add(
              attribute.type() + " " + attribute.shaderName() + "|"
                  + switch (attribute.legacyName()) {
                    case "mc_Entity" -> attribute.type().equals("vec4") ? "vec4(-1.0, -1.0, 0.0, 1.0)" : attribute.type() + "(-1)";
                    case "at_tangent" -> attribute.type().equals("vec4") ? "vec4(1.0, 0.0, 0.0, 1.0)" : attribute.type() + "(1.0, 0.0, 0.0)";
                    default -> attribute.type().equals("vec4") ? "vec4(0.0, 0.0, 0.0, 1.0)" : attribute.type() + "(0)";
                  });
      }
      attributes.clear();
    }
    for (var builtin :
        BUILTIN_UNIFORMS.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      if (token(vertexBody, builtin.getKey()) || token(fragmentBody, builtin.getKey())) {
        String name = builtin.getKey().replace("gl_", "sl_");
        merge(
            uniforms,
            name,
            new UniformLayout.Declaration(
                builtin.getValue(), builtin.getKey().equals("gl_TextureMatrix") ? 8 : 0));
        vertexBody = replaceToken(vertexBody, builtin.getKey(), name);
        fragmentBody = replaceToken(fragmentBody, builtin.getKey(), name);
      }
    }
    for (var attribute : BUILTIN_ATTRIBUTES) {
      if (token(fragmentBody, attribute.legacyName()))
        throw unsupported(label, "fixed-function fragment input " + attribute.legacyName());
      if (token(vertexBody, attribute.legacyName())) {
        if (adapter != null) {
          // Cadence: a fixed-function input this draw's vertex format lacks (Sildur's clouds read gl_MultiTexCoord1, the
          // lightmap coordinate, which clouds don't have): full-bright light, white, facing up, as OptiFine's defaults
          String value =
              switch (attribute.legacyName()) {
                case "gl_MultiTexCoord1" -> "vec4(240.0, 240.0, 0.0, 1.0)";
                case "gl_Color" -> "vec4(1.0)";
                case "gl_Normal" -> "vec3(0.0, 1.0, 0.0)";
                case "gl_MultiTexCoord0" -> "vec4(0.0, 0.0, 0.0, 1.0)";
                default -> null;
              };
          if (value == null) throw unsupported(label, "vertex adapter mapping for " + attribute.legacyName());
          zeroInputs.add(attribute.type() + " " + attribute.shaderName() + "|" + value);
          vertexBody = replaceToken(vertexBody, attribute.legacyName(), attribute.shaderName());
          continue;
        }
        attributes.add(attribute);
        vertexBody = replaceToken(vertexBody, attribute.legacyName(), attribute.shaderName());
      }
    }
    attributes.sort(java.util.Comparator.comparingInt(TranslatedProgram.Attribute::location));
    Set<String> knownVertex =
        new LinkedHashSet<>(
            List.of(
                "gl_Position",
                "gl_VertexIndex",
                "gl_InstanceIndex",
                "gl_ClipDistance",
                "gl_PointSize"));
    Set<String> knownFragment =
        new LinkedHashSet<>(
            List.of(
                "gl_FragCoord",
                "gl_FrontFacing",
                "gl_FragDepth",
                "gl_PointCoord",
                "gl_SampleID",
                "gl_SamplePosition",
                "gl_SampleMaskIn",
                "gl_SampleMask"));
    vertexBody =
        replaceToken(
            replaceToken(vertexBody, "gl_VertexID", "gl_VertexIndex"),
            "gl_InstanceID",
            "gl_InstanceIndex");
    List<Integer> drawBuffers = f.drawBuffers();
    Matcher output = OUTPUT.matcher(fragmentBody);
    StringBuffer lowered = new StringBuffer();
    int outputCount = 0;
    while (output.find()) {
      int index = Integer.parseInt(output.group(1));
      if (index >= 8) throw unsupported(label, "more than eight simultaneous color outputs");
      outputCount = Math.max(outputCount, index + 1);
      output.appendReplacement(lowered, "sl_FragData" + index);
    }
    output.appendTail(lowered);
    fragmentBody = lowered.toString();
    if (token(fragmentBody, "gl_FragColor")) {
      fragmentBody = replaceToken(fragmentBody, "gl_FragColor", "sl_FragData0");
      outputCount = Math.max(outputCount, 1);
    }
    if (!modernOutputs.isEmpty()) {
      // Cadence: `out` variables stay the pack's own globals; a wrapper main copies each to its location's output at the
      // end, widened to vec4 as OpenGL widens a narrower output (alpha 1).
      Matcher packMain = MAIN.matcher(fragmentBody);
      if (!packMain.find()) throw new IllegalArgumentException("Missing fragment main in " + label);
      StringBuilder copies = new StringBuilder();
      StringBuilder globals = new StringBuilder();
      for (String[] out : modernOutputs) { // {location, type, name}
        int index = Integer.parseInt(out[0]);
        if (index >= 8) throw unsupported(label, "more than eight simultaneous color outputs");
        if (!out[1].matches("float|vec[234]|u?int|[iu]vec[234]")) throw unsupported(label, "fragment output type " + out[1]);
        outputCount = Math.max(outputCount, index + 1);
        globals.append(out[1]).append(' ').append(out[2]).append(";\n");
        // Cadence: an integer output (Noble's `out uvec4 dataOut` into an RGBA32UI buffer) stays integer, widened the same way
        String four = out[1].startsWith("u") ? "uvec4" : out[1].startsWith("i") ? "ivec4" : "vec4";
        if (!four.equals("vec4")) integerOutputs.put(index, four);
        String zero = four.equals("uvec4") ? "0u" : four.equals("ivec4") ? "0" : "0.0";
        String one = four.equals("uvec4") ? "1u" : four.equals("ivec4") ? "1" : "1.0";
        String value =
            switch (out[1]) {
              case "vec4", "uvec4", "ivec4" -> out[2];
              case "vec3", "uvec3", "ivec3" -> four + "(" + out[2] + ", " + one + ")";
              case "vec2", "uvec2", "ivec2" -> four + "(" + out[2] + ", " + zero + ", " + one + ")";
              default -> four + "(" + out[2] + ", " + zero + ", " + zero + ", " + one + ")";
            };
        copies.append("sl_FragData").append(index).append(" = ").append(value).append(";\n");
      }
      fragmentBody =
          globals
              + packMain.replaceFirst("void sl_pack_outputs_main()")
              + "\nvoid main() {\nsl_pack_outputs_main();\n"
              + copies
              + "}\n";
    }
    if (drawBuffers.isEmpty()) {
      List<Integer> inferred = new ArrayList<>();
      for (int i = 0; i < outputCount; i++) inferred.add(i);
      drawBuffers = List.copyOf(inferred);
    }
    // Cadence: OptiFine and Iris drop writes to outputs past the declared draw buffers (Sildur's writes gl_FragData[1]
    // with DRAWBUFFERS:0): those become plain variables nothing reads.
    int boundOutputs = Math.min(outputCount, drawBuffers.size());
    boolean alphaDemoted = false;
    // Cadence: a program that writes no colour (VanillaEX's clouds only discard) has nothing to alpha test, nor one whose
    // first output is an integer (no alpha to test)
    if (alphaTest.enabled() && outputCount > 0 && !integerOutputs.containsKey(0)) {
      merge(uniforms, "alphaTestRef", new UniformLayout.Declaration("float", 0));
      if (earlyAlphaDemote) {
        var demotion = EarlyAlphaDemotion.apply(fragmentBody, alphaTest);
        fragmentBody = demotion.source();
        alphaDemoted = demotion.applied();
      }
      Matcher fragmentMain = MAIN.matcher(fragmentBody);
      if (!fragmentMain.find())
        throw new IllegalArgumentException("Missing fragment main in " + label);
      fragmentBody =
          fragmentMain.replaceFirst("void sl_pack_fragment_main()")
              + "\nvoid main() { sl_pack_fragment_main(); if (!("
              + alphaTest.passCondition("sl_FragData0.a", "alphaTestRef")
              + ")) discard; }\n";
    }
    // Cadence: a world program writing gl_FragDepth (Pegasus's and other packs' parallax: the surface pushed into the block)
    // loses early depth rejection: every hidden layer of terrain is shaded in full. Promising depth_greater (never nearer than
    // the rasterized surface, enforced by the max) gives it back; the depth test of pack geometry is always LESS(_EQUAL).
    if (CONSERVATIVE_DEPTH && mode == VertexMode.GEOMETRY && token(fragmentBody, "gl_FragDepth")) {
      Matcher fragmentMain = MAIN.matcher(fragmentBody);
      if (!fragmentMain.find())
        throw new IllegalArgumentException("Missing fragment main in " + label);
      fragmentBody =
          "layout(depth_greater) out float gl_FragDepth;\n"
              + fragmentMain.replaceFirst("void sl_depth_fragment_main()")
              + "\nvoid main() { gl_FragDepth = gl_FragCoord.z; sl_depth_fragment_main();"
              + " gl_FragDepth = max(gl_FragDepth, gl_FragCoord.z); }\n";
    }
    validate(vertexBody, knownVertex, label);
    validate(fragmentBody, knownFragment, label);
    UniformLayout layout = UniformLayout.of(uniforms, uniformDefaults);
    // Cadence: both stages share one uniform block, but a member only the other stage declared is renamed in this one, so
    // it can't clash with this stage's own global of that name (Bliss: a fragment `uniform vec3 sunVec` and a vertex
    // global `vec3 sunVec`). Same members, same offsets: only the name in this stage's source differs.
    Set<String> hideInVertex = new LinkedHashSet<>(fragmentUniforms), hideInFragment = new LinkedHashSet<>(vertexUniforms);
    hideInVertex.removeAll(vertexUniforms);
    hideInFragment.removeAll(fragmentUniforms);
    StringBuilder common = new StringBuilder();
    // Renaming the legacy sampler called `texture` avoids collision with modern texture().
    samplers.forEach(
        (name, type) ->
            common
                .append("uniform ")
                .append(
                    type.equals("sampler2DShadow") && shadowComparison == ShadowComparison.EMULATED
                        ? "sampler2D"
                        : type)
                .append(' ')
                .append(name.equals("texture") ? "sl_texture" : name)
                .append(";\n"));
    String vertexCommon = "#version 450\n" + layout.declaration(hideInVertex) + common + storage.header();
    String fragmentCommon = "#version 450\n" + layout.declaration(hideInFragment) + common + storage.header();
    StringBuilder vin = new StringBuilder();
    if (adapter != null) vin.append(adapterDeclarations).append('\n');
    for (String zero : zeroInputs) { // "type name" (zero) or "type name|value"
      int bar = zero.indexOf('|');
      String declaration = bar < 0 ? zero : zero.substring(0, bar);
      vin.append(declaration).append(" = ")
          .append(bar < 0 ? zero.split(" ")[0] + "(0.0)" : zero.substring(bar + 1)).append(";\n");
    }
    for (var attribute : attributes) {
      if (mode == VertexMode.FULLSCREEN) {
        if (attribute.legacyName().equals("gl_Vertex")) vin.append("vec4 sl_Vertex;\n");
        else if (attribute.legacyName().equals("gl_MultiTexCoord0"))
          vin.append("vec4 sl_MultiTexCoord0;\n");
        // Cadence: OptiFine draws its full-screen quad in white, with no lightmap coordinate
        else if (attribute.legacyName().equals("gl_Color")) vin.append("vec4 sl_Color = vec4(1.0);\n");
        // any other vertex input on the full-screen quad (I Like Vanilla's composite declares mc_Entity): OpenGL's value
        // for an attribute no buffer feeds, (0, 0, 0, 1)
        else
          vin.append(attribute.type())
              .append(' ')
              .append(attribute.shaderName())
              .append(attribute.type().equals("vec4") ? " = vec4(0.0, 0.0, 0.0, 1.0);\n"
                  : " = " + attribute.type() + "(0);\n");
      } else
        vin.append("layout(location=")
            .append(attribute.location())
            .append(") in ")
            .append(attribute.type())
            .append(' ')
            .append(attribute.shaderName())
            .append(";\n");
    }
    StringBuilder vout = new StringBuilder();
    StringBuilder fin = new StringBuilder();
    // Cadence: a varying of the pack's own struct type (Photon: `flat out OverworldFogParameters fog_params;`) is declared
    // here, above the pack's code, so its struct definition moves up with it (out of both stages' code), and the varying
    // takes as many locations as the struct's members do.
    Map<String, Integer> structSlots = new HashMap<>();
    Map<String, String> structMembers = new HashMap<>();
    for (var varying : varyings.values()) {
      String type = varying.type();
      if (type.matches("[ibud]?vec[234]|mat[234](x[234])?|float|int|uint|bool|double") || structSlots.containsKey(type)) continue;
      Pattern definition = Pattern.compile("\\bstruct\\s+" + Pattern.quote(type) + "\\s*\\{([^}]*)\\}\\s*;");
      Matcher inVertex = definition.matcher(vertexBody), inFragment = definition.matcher(fragmentBody);
      Matcher found = inVertex.find() ? inVertex : inFragment.find() ? inFragment : null;
      if (found == null) continue;
      String text = found.group();
      structSlots.put(type, structSlots(found.group(1), structSlots));
      structMembers.put(type, found.group(1));
      vertexBody = vertexBody.replace(text, "");
      fragmentBody = fragmentBody.replace(text, "");
      vout.append(text).append('\n');
      fin.append(text).append('\n');
    }
    // ...and since the game's pipeline layer takes no struct in a stage's interface, each such varying becomes one varying
    // per member: the pack's code keeps the struct as a plain global in both stages, the vertex stage copies its members
    // out after the pack's main, the fragment stage copies them in before it.
    StringBuilder structCopyOut = new StringBuilder(), structCopyIn = new StringBuilder();
    for (var varying : List.copyOf(varyings.entrySet())) {
      String members = structMembers.get(varying.getValue().type());
      if (members == null) continue;
      if (varying.getValue().arrayLength() > 0) throw unsupported(label, "arrays of struct varyings");
      String name = varying.getKey(), qualifier = varyingQualifiers.getOrDefault(name, "");
      varyings.remove(name);
      varyingQualifiers.remove(name);
      vout.append(varying.getValue().type()).append(' ').append(name).append(";\n");
      fin.append(varying.getValue().type()).append(' ').append(name).append(";\n");
      Matcher member = Pattern.compile("(\\w+)\\s+(\\w+)\\s*(?:\\[\\s*(\\d+)\\s*\\])?\\s*;")
          .matcher(members.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", ""));
      while (member.find()) {
        if (structMembers.containsKey(member.group(1))) throw unsupported(label, "nested struct varyings");
        String flat = "sl_struct_" + name + "_" + member.group(2);
        varyings.put(flat, new UniformLayout.Declaration(member.group(1), member.group(3) == null ? 0 : Integer.parseInt(member.group(3))));
        varyingQualifiers.put(flat, qualifier);
        structCopyOut.append(flat).append(" = ").append(name).append('.').append(member.group(2)).append(";\n");
        structCopyIn.append(name).append('.').append(member.group(2)).append(" = ").append(flat).append(";\n");
      }
    }
    if (structCopyIn.length() > 0) {
      Matcher fragmentMain = MAIN.matcher(fragmentBody);
      if (!fragmentMain.find()) throw new IllegalArgumentException("Missing fragment main in " + label);
      fragmentBody = fragmentMain.replaceFirst("void sl_struct_fragment_main()")
          + "\nvoid main() {\n" + structCopyIn + "sl_struct_fragment_main();\n}\n";
    }
    int location = 0;
    for (var varying : varyings.entrySet()) {
      String suffix =
          varying.getValue().arrayLength() > 0 ? "[" + varying.getValue().arrayLength() + "]" : "";
      String declaration = varying.getValue().type() + " " + varying.getKey() + suffix + ";\n";
      String interpolation = varyingQualifiers.getOrDefault(varying.getKey(), "");
      if (interpolation.isEmpty() && varying.getValue().type().matches("[ibu].*"))
        interpolation = "flat";
      if (!interpolation.isEmpty()) interpolation += " ";
      vout.append("layout(location=")
          .append(location)
          .append(") ")
          .append(interpolation)
          .append("out ")
          .append(declaration);
      fin.append("layout(location=")
          .append(location)
          .append(") ")
          .append(interpolation)
          .append("in ")
          .append(declaration);
      location += structSlots.containsKey(varying.getValue().type())
          ? structSlots.get(varying.getValue().type()) * Math.max(1, varying.getValue().arrayLength())
          : varyingLocations(varying.getValue());
    }
    StringBuilder fout = new StringBuilder();
    for (int i = 0; i < outputCount; i++)
      if (i < boundOutputs)
        fout.append("layout(location=")
            .append(i)
            .append(") out ")
            .append(integerOutputs.getOrDefault(i, "vec4"))
            .append(" sl_FragData")
            .append(i)
            .append(";\n");
      else fout.append(integerOutputs.getOrDefault(i, "vec4")).append(" sl_FragData").append(i).append(";\n");
    vertexBody = modernize(vertexBody, samplers);
    fragmentBody = modernize(fragmentBody, samplers);
    Matcher main = MAIN.matcher(vertexBody);
    if (!main.find()) throw new IllegalArgumentException("Missing vertex main in " + label);
    vertexBody = main.replaceFirst("void sl_pack_main()");
    String entry = "\nvoid main() {\n";
    if (adapter != null) entry += adapter.initialization() + "\n";
    // Cadence: every output starts at zero, as OpenGL drivers leave an output a pack never writes in practice (Sildur's
    // Enhanced declares `dist` and never sets it; on Metal it held garbage and fogged the whole picture)
    for (var varying : varyings.entrySet()) {
      String type = varying.getValue().type();
      String zero = type.startsWith("mat") ? type + "(0.0)" : type.matches("[ibu]?vec[234]|float|int|uint|bool") ? type + "(0)" : null;
      if (zero == null) continue;
      if (varying.getValue().arrayLength() > 0)
        entry += "for (int sl_i = 0; sl_i < " + varying.getValue().arrayLength() + "; ++sl_i) " + varying.getKey() + "[sl_i] = " + zero + ";\n";
      else entry += varying.getKey() + " = " + zero + ";\n";
    }
    if (mode == VertexMode.FULLSCREEN) {
      // Cadence: Iris's full-screen quad: corners 0..1 for gl_Vertex and the texture coordinate, and an orthographic
      // projection to the screen (FrameUniforms). Was one oversized triangle in clip space: a pack that shrinks the quad
      // in its vertex shader to draw at lower resolution (Bliss) got half of its rectangle (a triangle) and garbage in
      // the other half, an olive haze over its night and underwater.
      entry += "const vec2 sl_corners[6] = vec2[6](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 0.0),"
          + " vec2(1.0, 1.0), vec2(0.0, 1.0));\n";
      entry += "vec2 sl_uv = sl_corners[gl_VertexIndex];\n";
      if (attributes.stream().anyMatch(a -> a.shaderName().equals("sl_Vertex")))
        entry += "sl_Vertex = vec4(sl_uv, 0.0, 1.0);\n";
      if (attributes.stream().anyMatch(a -> a.shaderName().equals("sl_MultiTexCoord0")))
        entry += "sl_MultiTexCoord0 = vec4(sl_uv, 0.0, 1.0);\n";
    }
    // Pack matrices and sampled depth remain OpenGL-style; rasterization expects [0, 1] NDC Z.
    entry += "sl_pack_main();\n" + adapterCopyOut + structCopyOut + "gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";
    String compatibility =
        shadowComparison == ShadowComparison.HARDWARE
            ? """
            float sl_shadowCompare(sampler2DShadow s, vec3 p) { return texture(s, p); }
            vec4 sl_shadow2D(sampler2DShadow s, vec3 p) { return vec4(sl_shadowCompare(s, p)); }
            """
            : """
            // Explicit four-tap comparison filtering preserves linear LEQUAL shadow sampling on
            // backends whose sampler interface does not expose hardware depth comparisons.
            float sl_shadowCompare(sampler2D s, vec3 p) {
              ivec2 extent = textureSize(s, 0);
              vec2 pixel = p.xy * vec2(extent) - 0.5;
              ivec2 base = ivec2(floor(pixel));
              vec2 weight = fract(pixel);
              ivec2 upper = extent - ivec2(1);
              float a = step(p.z, texelFetch(s, clamp(base, ivec2(0), upper), 0).r);
              float b = step(p.z, texelFetch(s, clamp(base + ivec2(1, 0), ivec2(0), upper), 0).r);
              float c = step(p.z, texelFetch(s, clamp(base + ivec2(0, 1), ivec2(0), upper), 0).r);
              float d = step(p.z, texelFetch(s, clamp(base + ivec2(1, 1), ivec2(0), upper), 0).r);
              return mix(mix(a, b, weight.x), mix(c, d, weight.x), weight.y);
            }
            vec4 sl_shadow2D(sampler2D s, vec3 p) { return vec4(sl_shadowCompare(s, p)); }
            """;
    TranslatedProgram translated =
        new TranslatedProgram(
            label + (alphaDemoted ? "/early_alpha_demote" : ""),
            escapeMetalKeywords(
                vertexCommon + vin.toString() + vout + compatibility + vertexBody + entry),
            escapeMetalKeywords(
                (alphaDemoted
                        ? fragmentCommon.replace(
                            "#version 450\n",
                            "#version 450\n" + "#extension GL_EXT_demote_to_helper_invocation : require\n")
                        : fragmentCommon)
                    + fin
                    + fout
                    + compatibility
                    + fragmentBody),
            layout,
            samplers.entrySet().stream()
                .map(e -> new TranslatedProgram.Sampler(e.getKey(), e.getValue()))
                .toList(),
            mode == VertexMode.FULLSCREEN
                ? List.of()
                : adapter == null ? attributes : adapter.attributes(),
            drawBuffers,
            v.metadataSource(),
            f.metadataSource(),
            storage.resources());
    return mode == VertexMode.FULLSCREEN
            && Boolean.getBoolean("cadence.shaders.liftUniformInitializers")
        ? UniformInitializerLifting.apply(translated).program()
        : translated;
  }

  public static String samplerShaderName(String name) {
    return name.equals("texture")
        ? "sl_texture"
        : METAL_KEYWORDS.contains(name) ? "sl_cpp_" + name : name;
  }

  private static String escapeMetalKeywords(String source) {
    Matcher tokens = Pattern.compile("\\b[A-Za-z_]\\w*\\b").matcher(source);
    StringBuffer escaped = new StringBuffer();
    while (tokens.find()) {
      if (METAL_KEYWORDS.contains(tokens.group())) {
        String replacement = "sl_cpp_" + tokens.group();
        if (token(source, replacement))
          throw new UnsupportedOperationException(
              "Shader identifier collides with reserved translation name " + replacement);
        tokens.appendReplacement(escaped, replacement);
      }
    }
    tokens.appendTail(escaped);
    return escaped.toString();
  }

  /** The GLSL type of an adapter expression, when known: a bare adapter input, or one of the vec4/vec3 forms it writes. */
  private static String expressionType(String legacy, String expression, Map<String, String> supplied) {
    if (supplied.containsKey(expression)) return supplied.get(expression);
    Matcher constructor = Pattern.compile("^(vec[234])\\(").matcher(expression);
    if (constructor.find() && expression.endsWith(")")) return constructor.group(1);
    if (legacy.equals("at_midBlock")) return "vec3";
    return null;
  }

  /** value (of type from) as type to, as OpenGL fills a vertex attribute: missing components 0, w 1; extra ones dropped. */
  static String convert(String value, String from, String to) {
    if (from.equals(to)) return value;
    int n = size(from), m = size(to);
    if (n == 0 || m == 0) return value;
    if (m <= n) return n == 1 ? to + "(" + value + ")" : to + "(" + value + "." + "xyzw".substring(0, m) + ")";
    StringBuilder fill = new StringBuilder(to).append('(').append(value);
    for (int i = n; i < m; i++) fill.append(i == 3 ? ", 1" : ", 0");
    return fill.append(')').toString();
  }

  private static int size(String type) {
    if (type.matches("float|int|uint|bool")) return 1;
    if (type.matches("[biu]?vec[234]")) return type.charAt(type.length() - 1) - '0';
    return 0;
  }

  /** Cadence: GLSL built-in functions added after GLSL 1.20, which older packs define themselves. */
  private static final List<String> LATER_BUILTINS =
      List.of("fma", "tanh", "sinh", "cosh", "asinh", "acosh", "atanh", "round", "roundEven", "trunc", "isnan", "isinf",
          "inverse", "determinant", "ldexp", "frexp", "bitCount", "findLSB", "findMSB", "packUnorm2x16", "unpackUnorm2x16",
          "packUnorm4x8", "unpackUnorm4x8", "packHalf2x16", "unpackHalf2x16", "floatBitsToInt", "floatBitsToUint",
          "intBitsToFloat", "uintBitsToFloat", "bitfieldExtract", "bitfieldInsert", "bitfieldReverse");

  /** Cadence: Iris core-profile attribute and uniform names, and the legacy built-in each equals. */
  private static final Map<String, String> IRIS_CORE =
      Map.ofEntries(
          Map.entry("vaPosition", "gl_Vertex.xyz"),
          Map.entry("vaColor", "gl_Color"),
          Map.entry("vaUV0", "gl_MultiTexCoord0.xy"),
          Map.entry("vaUV1", "ivec2(0, 10)"), // the overlay coordinate of "no overlay"
          Map.entry("vaUV2", "ivec2(gl_MultiTexCoord1.xy)"),
          Map.entry("vaNormal", "gl_Normal"),
          Map.entry("chunkOffset", "vec3(0.0)"), // gl_Vertex already includes it
          Map.entry("modelViewMatrix", "gl_ModelViewMatrix"),
          Map.entry("modelViewMatrixInverse", "inverse(gl_ModelViewMatrix)"),
          Map.entry("projectionMatrix", "gl_ProjectionMatrix"),
          Map.entry("projectionMatrixInverse", "inverse(gl_ProjectionMatrix)"),
          Map.entry("normalMatrix", "gl_NormalMatrix"),
          Map.entry("textureMatrix", "gl_TextureMatrix[0]"));
  private static final Pattern MODERN =
      Pattern.compile(
          "(?m)^[ \\t]*(?:layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:flat|smooth|noperspective|centroid)\\s+)*)(in|out)\\s+"
              + "(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+([^;{}()=]+);");
  private static final Pattern LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\d+)");
  private static final Pattern ARRAY_ON_TYPE =
      Pattern.compile(
          "(?m)^([ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:flat|smooth|noperspective|centroid|uniform|varying|in|out|attribute|highp|mediump|lowp)\\s+)+)"
              + "(\\w+)\\s*\\[\\s*(\\w+)\\s*\\]\\s+(\\w+)\\s*;");

  /** Cadence: `flat out vec3[9] sky;` (the array on the type, Noble) as `flat out vec3 sky[9];`, the form read below. */
  static String arraysOnNames(String source) {
    return ARRAY_ON_TYPE.matcher(source).replaceAll("$1$2 $4[$3];");
  }

  /**
   * Cadence: global in/out declarations as the legacy storage the rest of the translator reads: a vertex stage's inputs are
   * attributes and its outputs varyings, a fragment stage's inputs varyings. A fragment stage's outputs are collected in
   * {@code outputs} as {location, type, name} (explicit layout locations, else in declaration order) and declared later.
   */
  static String modernInOut(String source, boolean vertex, List<String[]> outputs) {
    Matcher m = MODERN.matcher(source);
    StringBuffer result = new StringBuffer();
    int next = 0;
    while (m.find()) {
      String layout = m.group(1), interpolation = m.group(2), direction = m.group(3), type = m.group(4);
      String replacement;
      if (vertex) replacement = direction.equals("in") ? "attribute " + type + " " + m.group(5) + ";"
          : interpolation + "varying " + type + " " + m.group(5) + ";";
      else if (direction.equals("in")) replacement = interpolation + "varying " + type + " " + m.group(5) + ";";
      else {
        Matcher location = layout == null ? null : LOCATION.matcher(layout);
        int index = location != null && location.find() ? Integer.parseInt(location.group(1)) : next;
        for (String name : m.group(5).split(",")) {
          outputs.add(new String[] {Integer.toString(index), type, name.strip()});
          next = ++index;
        }
        replacement = "";
      }
      m.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(result);
    return result.toString();
  }

  /**
   * Cadence: the pack's storage resources as declarations need them: each image's GLSL format qualifier (for one declared
   * without), and the sampler names that read custom images (they go to set 1, like every 3D sampler).
   */
  public record StorageContext(Map<String, String> imageFormats, Set<String> imageSamplers) {
    public static final StorageContext NONE = new StorageContext(Map.of(), Set.of());

    public StorageContext {
      imageFormats = Map.copyOf(imageFormats);
      imageSamplers = Set.copyOf(imageSamplers);
    }
  }

  private StorageContext storageContext = StorageContext.NONE;

  public synchronized void storageContext(StorageContext context) {
    storageContext = java.util.Objects.requireNonNull(context);
  }

  private static final Pattern IMAGE_DECLARATION =
      Pattern.compile(
          "(?m)^[ \\t]*((?:(?:layout\\s*\\([^)]*\\)|readonly|writeonly|coherent|volatile|restrict|uniform|highp|mediump|lowp)\\s*)+?)"
              + "([iu]?image(?:2D|3D|2DArray))\\s+(\\w+)\\s*;");
  private static final Pattern IMAGE_DECLARATIONS =
      Pattern.compile(
          "(?m)^[ \\t]*((?:(?:layout\\s*\\([^)]*\\)|readonly|writeonly|coherent|volatile|restrict|uniform|highp|mediump|lowp)\\s*)+?)"
              + "([iu]?image(?:2D|3D|2DArray))\\s+(\\w+(?:\\s*,\\s*\\w+)+)\\s*;");
  private static final Pattern SET1_SAMPLER =
      Pattern.compile(
          "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?([iu]?sampler(?:2D|3D|2DArray))\\s+(\\w+)\\s*;");
  private static final Pattern BUFFER_BLOCK =
      Pattern.compile(
          "(?ms)^[ \\t]*layout\\s*\\(([^)]*)\\)\\s*((?:(?:readonly|writeonly|coherent|volatile|restrict)\\s+)*)buffer\\s+(\\w+)\\s*\\{(.*?)\\}\\s*(\\w*)\\s*;");
  private static final Pattern QUALIFIER = Pattern.compile("\\b(readonly|writeonly|coherent|volatile|restrict)\\b");

  /**
   * Cadence: takes a pack's image, buffer object and set-1 sampler declarations out of a stage's source and gives each name
   * one set-1 binding, re-declared in the header (header()) of every stage that the scan saw: the game's pipeline model
   * never sees them, the Metal backend binds them at fixed slots (MetalStorage).
   */
  private final class StorageScan {
    private final Map<String, TranslatedProgram.Storage> byName = new LinkedHashMap<>();
    private final Map<String, String> text = new LinkedHashMap<>();

    String extract(String source) {
      Matcher blocks = BUFFER_BLOCK.matcher(source);
      StringBuffer out = new StringBuffer();
      while (blocks.find()) {
        String layout = blocks.group(1);
        Matcher b = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)").matcher(layout);
        int index = b.find() ? Integer.parseInt(b.group(1)) : 0;
        String key = "buffer#" + index;
        if (!byName.containsKey(key)) {
          int binding = byName.size();
          byName.put(key, new TranslatedProgram.Storage(blocks.group(3), TranslatedProgram.Storage.Kind.BUFFER, "buffer", binding, index));
          String rest = layout.replaceAll("\\bbinding\\s*=\\s*\\d+\\s*,?", "").replaceAll(",\\s*$", "").strip();
          text.put(key, "layout(" + (rest.isEmpty() ? "" : rest + ", ") + "set = 1, binding = " + binding + ") "
              + blocks.group(2) + "buffer " + blocks.group(3) + " {" + blocks.group(4) + "} " + blocks.group(5) + ";\n");
        }
        blocks.appendReplacement(out, "");
      }
      blocks.appendTail(out);
      source = out.toString();
      // Cadence: several images in one declaration (Solas: `writeonly uniform image3D floodfill_img, floodfill_img_copy;`)
      // become one declaration each, the form the scan below reads
      Matcher several = IMAGE_DECLARATIONS.matcher(source);
      out = new StringBuffer();
      while (several.find()) {
        StringBuilder each = new StringBuilder();
        for (String name : several.group(3).split(","))
          each.append(several.group(1)).append(several.group(2)).append(' ').append(name.strip()).append(";\n");
        several.appendReplacement(out, Matcher.quoteReplacement(each.toString()));
      }
      several.appendTail(out);
      source = out.toString();
      Matcher images = IMAGE_DECLARATION.matcher(source);
      out = new StringBuffer();
      while (images.find()) {
        String prefix = images.group(1);
        if (!Pattern.compile("\\buniform\\b").matcher(prefix).find()) {
          images.appendReplacement(out, Matcher.quoteReplacement(images.group()));
          continue;
        }
        String type = images.group(2), name = images.group(3);
        if (!byName.containsKey(name)) {
          int binding = byName.size();
          byName.put(name, new TranslatedProgram.Storage(name, TranslatedProgram.Storage.Kind.IMAGE, type, binding, -1));
          Matcher layout = Pattern.compile("layout\\s*\\(([^)]*)\\)").matcher(prefix);
          String format = null;
          while (layout.find())
            for (String part : layout.group(1).split(","))
              if (part.strip().matches("[a-z0-9_]+") && !part.strip().matches("binding|set|location")) format = part.strip();
          if (format == null) format = storageContext.imageFormats().get(name);
          StringBuilder qualifiers = new StringBuilder();
          Matcher q = QUALIFIER.matcher(prefix);
          while (q.find()) qualifiers.append(q.group(1)).append(' ');
          text.put(name, "layout(set = 1, binding = " + binding + (format == null ? "" : ", " + format) + ") " + qualifiers
              + "uniform " + type + " " + name + ";\n");
        }
        images.appendReplacement(out, "");
      }
      images.appendTail(out);
      source = out.toString();
      Matcher samplers = SET1_SAMPLER.matcher(source);
      out = new StringBuffer();
      while (samplers.find()) {
        String type = samplers.group(1), name = samplers.group(2);
        if (!type.endsWith("3D") && !type.endsWith("2DArray") && !storageContext.imageSamplers().contains(name)) {
          samplers.appendReplacement(out, Matcher.quoteReplacement(samplers.group()));
          continue;
        }
        if (!byName.containsKey(name)) {
          int binding = byName.size();
          byName.put(name, new TranslatedProgram.Storage(name, TranslatedProgram.Storage.Kind.SAMPLED, type, binding, -1));
          text.put(name, "layout(set = 1, binding = " + binding + ") uniform " + type + " " + name + ";\n");
        }
        samplers.appendReplacement(out, "");
      }
      samplers.appendTail(out);
      return out.toString();
    }

    String header() {
      return String.join("", text.values());
    }

    List<TranslatedProgram.Storage> resources() {
      return List.copyOf(byName.values());
    }
  }

  /**
   * Cadence: an Iris compute program (.csh) for Metal: the uniform block at set 0 binding 0, combined samplers at set 0
   * bindings 1..n, the pack's images and buffer objects in set 1 (StorageScan), the program's own
   * layout(local_size_...) kept. Returned as a TranslatedProgram (so the translation cache keeps it too): vertexSource is
   * the kernel's GLSL, fragmentSource empty, drawBuffers the local size {x, y, z}.
   */
  public synchronized TranslatedProgram translateCompute(String source, String label) {
    if (compiler == 0) throw new IllegalStateException("Shader compiler is closed");
    Object[] key = {"compute", source, label, shadowComparison, storageContext};
    return TranslationCache.get(key, () -> translateComputeNow(source, label));
  }

  private static final Pattern LOCAL_SIZE = Pattern.compile("layout\\s*\\(([^)]*local_size[^)]*)\\)\\s*in\\s*;");

  private TranslatedProgram translateComputeNow(String source, String label) {
    uniformDefaults = new LinkedHashMap<>();
    zeroInputs = new LinkedHashSet<>();
    vertexUniforms = new LinkedHashSet<>();
    fragmentUniforms = new LinkedHashSet<>();
    Preprocessed c = preprocess(source, shaderc_compute_shader, label + ".csh");
    Map<String, UniformLayout.Declaration> uniforms = new LinkedHashMap<>();
    Map<String, String> samplers = new LinkedHashMap<>();
    StorageScan storage = new StorageScan();
    String body =
        declarations(storage.extract(c.source()), false, uniforms, samplers, new LinkedHashMap<>(), new LinkedHashMap<>(),
            new ArrayList<>());
    body = SharedVec3Split.apply(body);
    for (var core : IRIS_CORE.entrySet()) {
      Pattern own = Pattern.compile("\\b(?:float|int|bool|[biu]?vec[234]|mat[234](?:x[234])?)\\s+" + core.getKey() + "\\b");
      if (!own.matcher(body).find()) body = replaceToken(body, core.getKey(), "(" + core.getValue() + ")");
    }
    for (String builtin : LATER_BUILTINS) {
      Pattern defined =
          Pattern.compile("\\b(?:float|int|uint|bool|[biu]?vec[234]|mat[234])\\s+" + builtin + "\\s*\\([^)]*\\)\\s*\\{");
      if (defined.matcher(body).find())
        body = Pattern.compile("\\b" + builtin + "(\\s*\\()").matcher(body).replaceAll("sl_pack_" + builtin + "$1");
    }
    for (var builtin : BUILTIN_UNIFORMS.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      if (token(body, builtin.getKey())) {
        String name = builtin.getKey().replace("gl_", "sl_");
        merge(uniforms, name, new UniformLayout.Declaration(builtin.getValue(), builtin.getKey().equals("gl_TextureMatrix") ? 8 : 0));
        body = replaceToken(body, builtin.getKey(), name);
      }
    }
    validate(
        body,
        Set.of("gl_GlobalInvocationID", "gl_LocalInvocationID", "gl_WorkGroupID", "gl_NumWorkGroups",
            "gl_LocalInvocationIndex", "gl_WorkGroupSize"),
        label);
    body = modernize(body, samplers);
    int[] local = {1, 1, 1};
    Matcher size = LOCAL_SIZE.matcher(body);
    if (!size.find()) throw unsupported(label, "compute program without layout(local_size_x ...) in");
    for (String part : size.group(1).split(",")) {
      String[] kv = part.split("=");
      if (kv.length != 2) continue;
      int value = Integer.parseInt(kv[1].strip());
      switch (kv[0].strip()) {
        case "local_size_x" -> local[0] = value;
        case "local_size_y" -> local[1] = value;
        case "local_size_z" -> local[2] = value;
        default -> {}
      }
    }
    UniformLayout layout = UniformLayout.of(uniforms, uniformDefaults);
    StringBuilder header = new StringBuilder("#version 450\n");
    header.append(layout.declaration().replaceFirst("layout\\(std140\\) uniform", "layout(std140, set = 0, binding = 0) uniform"));
    int binding = 1;
    for (var sampler : samplers.entrySet()) {
      String type =
          sampler.getValue().equals("sampler2DShadow") && shadowComparison == ShadowComparison.EMULATED
              ? "sampler2D"
              : sampler.getValue();
      header.append("layout(set = 0, binding = ").append(binding++).append(") uniform ").append(type).append(' ')
          .append(sampler.getKey().equals("texture") ? "sl_texture" : sampler.getKey()).append(";\n");
    }
    header.append(storage.header());
    String compatibility =
        shadowComparison == ShadowComparison.HARDWARE
            ? "float sl_shadowCompare(sampler2DShadow s, vec3 p) { return texture(s, p); }\n"
                + "vec4 sl_shadow2D(sampler2DShadow s, vec3 p) { return vec4(sl_shadowCompare(s, p)); }\n"
            : "float sl_shadowCompare(sampler2D s, vec3 p) { return step(p.z, textureLod(s, p.xy, 0.0).r); }\n"
                + "vec4 sl_shadow2D(sampler2D s, vec3 p) { return vec4(sl_shadowCompare(s, p)); }\n";
    return new TranslatedProgram(
        label,
        escapeMetalKeywords(header + compatibility + body),
        "",
        layout,
        samplers.entrySet().stream().map(e -> new TranslatedProgram.Sampler(e.getKey(), e.getValue())).toList(),
        List.of(),
        List.of(local[0], local[1], local[2]),
        c.metadataSource(),
        "",
        storage.resources());
  }

  /** Cadence: initial values of uniforms declared with one, for the program being translated (see declarations). */
  private Map<String, float[]> uniformDefaults = new LinkedHashMap<>();
  /** Cadence: vertex inputs the game doesn't supply, declared as zero-valued globals ("type name"). */
  private Set<String> zeroInputs = new LinkedHashSet<>();
  /** Cadence: the uniforms each stage declares itself (see UniformLayout.declaration(hidden)). */
  private Set<String> vertexUniforms = new LinkedHashSet<>(), fragmentUniforms = new LinkedHashSet<>();

  private String declarations(
      String source,
      boolean vertex,
      Map<String, UniformLayout.Declaration> uniforms,
      Map<String, String> samplers,
      Map<String, UniformLayout.Declaration> varyings,
      Map<String, String> varyingQualifiers,
      List<TranslatedProgram.Attribute> attributes) {
    Matcher matcher = DECLARATION.matcher(source);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      String qualifier = matcher.group(1).trim().replaceAll("\\s+", " ");
      String kind = matcher.group(2), type = matcher.group(4);
      String declarators = matcher.group(5);
      // Cadence: `uniform bool heavyFog = false;` (GLSL 1.20+): the value a uniform has until the host sets one. One
      // declarator only (the initializer may itself contain commas); the value is used when the game doesn't supply it.
      int equals = declarators.indexOf('=');
      if (equals > 0 && kind.equals("uniform")) {
        Matcher name = NAME.matcher(declarators.substring(0, equals).strip());
        if (!name.matches() || name.group(2) != null)
          throw new UnsupportedOperationException("Unsupported uniform declaration: " + matcher.group());
        float[] value = UniformDefaults.parse(type, declarators.substring(equals + 1).strip());
        if (value == null)
          throw new UnsupportedOperationException("Unsupported uniform initializer: " + matcher.group());
        merge(uniforms, name.group(1), new UniformLayout.Declaration(type, 0));
        (vertex ? vertexUniforms : fragmentUniforms).add(name.group(1));
        float[] previous = uniformDefaults.putIfAbsent(name.group(1), value);
        if (previous != null && !java.util.Arrays.equals(previous, value))
          throw new IllegalArgumentException("Conflicting uniform initializers: " + name.group(1));
        matcher.appendReplacement(result, "");
        continue;
      }
      for (String part : declarators.split(",")) {
        Matcher name = NAME.matcher(part.strip());
        if (!name.matches())
          throw new UnsupportedOperationException(
              "Unsupported " + kind + " declaration: " + matcher.group());
        String identifier = name.group(1);
        int length = name.group(2) == null ? 0 : Integer.parseInt(name.group(2));
        if (length == 0 && name.group(2) != null)
          throw new IllegalArgumentException("Zero-length array: " + identifier);
        var declaration = new UniformLayout.Declaration(type, length);
        switch (kind) {
          case "uniform" -> {
            if (type.matches("[iu]?sampler.*")) {
              if (length > 0)
                throw new UnsupportedOperationException("Sampler arrays: " + identifier);
              if (!type.matches("sampler2D|sampler2DShadow|samplerCube|[iu]sampler2D|sampler3D"))
                throw new UnsupportedOperationException(
                    "Unsupported sampler type " + type + " for " + identifier);
              String previous = samplers.putIfAbsent(identifier, type);
              if (previous != null && !previous.equals(type))
                throw new IllegalArgumentException("Conflicting sampler types: " + identifier);
            } else if (!IRIS_CORE.containsKey(identifier)) {
              merge(uniforms, identifier, declaration);
              (vertex ? vertexUniforms : fragmentUniforms).add(identifier);
            }
          }
          case "varying" -> {
            merge(varyings, identifier, declaration);
            String previous = varyingQualifiers.putIfAbsent(identifier, qualifier);
            if (previous != null && !previous.equals(qualifier))
              throw new IllegalArgumentException(
                  "Conflicting interpolation qualifiers: " + identifier);
          }
          case "attribute" -> {
            // Cadence: some packs (MakeUp) declare Iris's per-draw blockEntityId as an attribute; it is the uniform
            if (IRIS_CORE.containsKey(identifier)) continue; // replaced below (IRIS_CORE)
            if (identifier.equals("blockEntityId") && length == 0 && type.equals("int")) {
              merge(uniforms, identifier, declaration);
              continue;
            }
            // Iris's at_velocity (a vertex's motion since the last frame, for motion blur and TAA): terrain doesn't
            // move, and Cadence doesn't track entity vertices yet, so zero, as Iris gives a draw without it.
            if (identifier.equals("at_velocity") && length == 0 && type.equals("vec3")) {
              zeroInputs.add(type + " " + identifier);
              continue;
            }
            if (!vertex || length > 0)
              throw new UnsupportedOperationException("Unsupported attribute: " + identifier);
            int location =
                switch (identifier) {
                  case "mc_Entity" -> 5;
                  case "mc_midTexCoord" -> 6;
                  case "at_tangent" -> 7;
                  case "at_midBlock" -> 8;
                  default ->
                      throw new UnsupportedOperationException(
                          "Unknown legacy attribute " + identifier);
                };
            attributes.add(new TranslatedProgram.Attribute(identifier, identifier, type, location));
          }
          default -> throw new AssertionError(kind);
        }
      }
      matcher.appendReplacement(result, "");
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private Preprocessed preprocess(String source, int kind, String label) {
    List<List<Integer>> directives = new ArrayList<>();
    Matcher matcher = DRAW.matcher(source);
    StringBuffer marked = new StringBuffer();
    while (matcher.find()) {
      String value = matcher.group(2).replace(" ", "");
      List<Integer> targets = new ArrayList<>();
      if (matcher.group(1).equals("DRAWBUFFERS")) {
        for (char c : value.toCharArray()) targets.add(c == 'N' || c == 'n' ? -1 : c - '0');
      } else {
        for (String field : value.split(","))
          targets.add(field.equalsIgnoreCase("N") ? -1 : Integer.parseInt(field));
      }
      matcher.appendReplacement(marked, "const int sl_directive_" + directives.size() + " = 0;");
      directives.add(List.copyOf(targets));
    }
    matcher.appendTail(marked);
    List<String> metadata = new ArrayList<>();
    // Cadence: line comments are skipped first; a "/*" inside one (Pegasus has some) started a "block comment" that ran
    // to the next "*/" and took the code between, main included. Replaced comments keep their line breaks.
    Matcher comment = Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/").matcher(marked);
    StringBuffer constants = new StringBuffer();
    while (comment.find()) {
      if (comment.group().startsWith("//")) continue;
      if (!Pattern.compile("\\bconst\\s+(?:bool|int|float|vec4)\\b")
          .matcher(comment.group())
          .find()) continue;
      metadata.add(comment.group());
      comment.appendReplacement(
          constants,
          "const int sl_metadata_" + (metadata.size() - 1) + " = 0;"
              + "\n".repeat((int) comment.group().chars().filter(ch -> ch == '\n').count()));
    }
    comment.appendTail(constants);
    String input = VERSION.matcher(constants).replaceAll("");
    // Preprocessing resolves preprocessor directives, not legacy shader syntax. Version 450
    // lets shaderc accept Vulkan preprocessing while preserving all active legacy expressions.
    input = "#version 450\n" + EXTENSION.matcher(input).replaceAll("");
    // Cadence: conditionals evaluated as Iris does (decimal numbers allowed), before the GLSL compiler sees them
    if (EVALUATE_CONDITIONALS) {
      String evaluated = Conditionals.apply(input);
      if (evaluated != null) input = evaluated;
    }
    // Cadence: OptiFine's and Iris's preprocessors let a later #define replace an earlier one (Pegasus redefines
    // ROUGH_FRESNEL); shaderc stops at "Macro redefined". An #undef just before every #define gives the same result.
    input = REDEFINE.matcher(input).replaceAll("#undef $2\n$1");
    // Cadence: conditionals left open at the end of the source (Pegasus leaves six) close there, as Iris's preprocessor
    // closes them; shaderc stops at "missing #endif".
    input = input + "\n#endif".repeat(unclosedConditionals(input));
    long options = shaderc_compile_options_initialize();
    long result = 0;
    var sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(input, false);
    var labelBytes = org.lwjgl.system.MemoryUtil.memUTF8(label);
    var entryBytes = org.lwjgl.system.MemoryUtil.memUTF8("main");
    try {
      result =
          shaderc_compile_into_preprocessed_text(
              compiler, sourceBytes, kind, labelBytes, entryBytes, options);
      if ((result == 0
              || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
          && System.getProperty("cadence.shaders.dumpFailedPreprocess") != null) {
        try { // -Dcadence.shaders.dumpFailedPreprocess=DIR: the exact text shaderc refused, for reading
          java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("cadence.shaders.dumpFailedPreprocess"));
          java.nio.file.Files.createDirectories(dir);
          java.nio.file.Files.writeString(dir.resolve(label.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt"), input);
        } catch (java.io.IOException ignored) {
          // a debugging aid only
        }
      }
      if (result == 0
          || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
        throw new IllegalArgumentException(
            "Preprocessing "
                + label
                + ": "
                + (result == 0 ? "no result" : shaderc_result_get_error_message(result)));
      String output =
          java.nio.charset.StandardCharsets.UTF_8
              .decode(shaderc_result_get_bytes(result))
              .toString();
      output = VERSION.matcher(LINE.matcher(output).replaceAll("")).replaceAll("");
      Matcher active = SENTINEL.matcher(output);
      List<Integer> drawBuffers = List.of();
      while (active.find()) drawBuffers = directives.get(Integer.parseInt(active.group(1)));
      output = active.replaceAll("");
      Matcher activeMetadata =
          Pattern.compile("const\\s+int\\s+sl_metadata_(\\d+)\\s*=\\s*0\\s*;").matcher(output);
      StringBuffer restored = new StringBuffer();
      while (activeMetadata.find())
        activeMetadata.appendReplacement(
            restored,
            Matcher.quoteReplacement(metadata.get(Integer.parseInt(activeMetadata.group(1)))));
      activeMetadata.appendTail(restored);
      return new Preprocessed(activeMetadata.replaceAll(""), drawBuffers, restored.toString());
    } finally {
      if (result != 0) shaderc_result_release(result);
      shaderc_compile_options_release(options);
      org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
      org.lwjgl.system.MemoryUtil.memFree(labelBytes);
      org.lwjgl.system.MemoryUtil.memFree(entryBytes);
    }
  }

  private static final Pattern COMMENT_OR_DIRECTIVE =
      Pattern.compile("/\\*[\\s\\S]*?\\*/|//[^\\n]*|(?m)^[ \\t]*#[ \\t]*(if|ifdef|ifndef|endif)\\b");

  /** #if/#ifdef/#ifndef without a matching #endif, comments excluded. */
  static int unclosedConditionals(String source) {
    Matcher m = COMMENT_OR_DIRECTIVE.matcher(source);
    int depth = 0;
    while (m.find()) {
      if (m.group(1) == null) continue;
      if (m.group(1).equals("endif")) depth = Math.max(0, depth - 1);
      else depth++;
    }
    return depth;
  }

  private String modernize(String source, Map<String, String> samplers) {
    Set<String> comparisonNames = new LinkedHashSet<>();
    samplers.forEach(
        (name, type) -> {
          if (type.equals("sampler2DShadow")) comparisonNames.add(name);
        });
    Matcher comparisonParameters = Pattern.compile("\\bsampler2DShadow\\s+(\\w+)").matcher(source);
    while (comparisonParameters.find()) comparisonNames.add(comparisonParameters.group(1));
    for (String name : comparisonNames)
      source =
          source.replaceAll(
              "\\btexture\\s*\\(\\s*" + Pattern.quote(name) + "\\s*,",
              "sl_shadowCompare(" + name + ",");
    if (shadowComparison == ShadowComparison.EMULATED)
      source = replaceToken(source, "sampler2DShadow", "sampler2D");
    if (samplers.containsKey("texture")) source = replaceToken(source, "texture", "sl_texture");
    for (String old : List.of("texture2D", "texture3D", "textureCube"))
      source = replaceToken(source, old, "texture");
    for (String old : List.of("texture2DLod", "texture2DLodARB", "texture3DLod", "textureCubeLod"))
      source = replaceToken(source, old, "textureLod");
    source = replaceToken(source, "texture2DGradARB", "textureGrad");
    // Cadence: GL_EXT_gpu_shader4 / older names drivers still accept (Bliss uses texelFetch2D)
    for (String old : List.of("texelFetch2D", "texelFetch3D", "texelFetch2DRect"))
      source = replaceToken(source, old, "texelFetch");
    source = replaceToken(source, "textureSize2D", "textureSize");
    source = replaceToken(source, "texture2DGrad", "textureGrad");
    source = replaceToken(source, "texture2DProjLod", "textureProjLod");
    source = replaceToken(source, "texture2DProj", "textureProj");
    source = replaceToken(source, "shadow2D", "sl_shadow2D");
    return source;
  }

  private static void validate(String source, Set<String> known, String label) {
    Matcher builtins = Pattern.compile("\\bgl_\\w+").matcher(source);
    while (builtins.find())
      if (!known.contains(builtins.group())) throw unsupported(label, builtins.group());
    // Cadence: images and buffer objects are supported (StorageScan); atomic counters are not
    if (Pattern.compile("\\batomic_uint\\b").matcher(source).find())
      throw unsupported(label, "atomic counters");
  }

  private static UnsupportedOperationException unsupported(String label, String feature) {
    return new UnsupportedOperationException(
        "Shader pack program " + label + " requires unsupported " + feature);
  }

  private static boolean token(String source, String token) {
    return Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(source).find();
  }

  private static String replaceToken(String source, String old, String replacement) {
    return source.replaceAll(
        "\\b" + Pattern.quote(old) + "\\b", Matcher.quoteReplacement(replacement));
  }

  private static void merge(
      Map<String, UniformLayout.Declaration> map, String name, UniformLayout.Declaration value) {
    var previous = map.putIfAbsent(name, value);
    if (previous != null && !previous.equals(value))
      throw new IllegalArgumentException("Conflicting declarations: " + name);
  }

  /** The interface locations a struct with these members takes: one per member, a matrix's columns and an array's elements each. */
  private static int structSlots(String members, Map<String, Integer> known) {
    String code = members.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    Matcher member = Pattern.compile("(\\w+)\\s+(\\w+)\\s*(?:\\[\\s*(\\d+)\\s*\\])?\\s*;").matcher(code);
    int slots = 0;
    while (member.find()) {
      String type = member.group(1);
      int each = type.matches("mat[234].*") ? type.charAt(3) - '0' : known.getOrDefault(type, 1);
      slots += each * (member.group(3) == null ? 1 : Integer.parseInt(member.group(3)));
    }
    return Math.max(1, slots);
  }

  private static int varyingLocations(UniformLayout.Declaration declaration) {
    int slots = declaration.type().startsWith("mat") ? declaration.type().charAt(3) - '0' : 1;
    return slots * Math.max(1, declaration.arrayLength());
  }

  @Override
  public synchronized void close() {
    if (compiler != 0) shaderc_compiler_release(compiler);
    compiler = 0;
  }

  private record Preprocessed(String source, List<Integer> drawBuffers, String metadataSource) {}
}
