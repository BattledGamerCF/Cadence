// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata from an active, preprocessed stage. The compiler adapter must preserve DRAWBUFFERS and
 * block-comment constants through preprocessing: those comments are part of the pack interface.
 */
public record ShaderDirectives(List<Integer> drawBuffers, Map<String, String> constants) {
  private static final Pattern TARGETS =
      Pattern.compile("/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([^*]*?)\\s*\\*/");
  private static final Pattern CONSTANT =
      Pattern.compile(
          "(?m)^[\\t ]*const[\\t ]+(?:bool|int|float|vec[234]|ivec[234])[\\t ]+"
              + "([A-Za-z_][A-Za-z_0-9]*)[\\t ]*=[\\t ]*([^;\\n]+);");
  private static final Pattern BUFFER =
      Pattern.compile(
          "(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])(Format|Clear|ClearColor|MipmapEnabled)");
  private static final Set<String> GLOBAL =
      Set.of(
          "shadowMapResolution",
          "shadowDistance",
          "shadowDistanceRenderMul",
          "shadowIntervalSize",
          "shadowMapFov",
          "shadowMapHalfPlane",
          "shadowMapBias",
          "sunPathRotation",
          "ambientOcclusionLevel",
          "noiseTextureResolution",
          "drynessHalflife",
          "wetnessHalflife",
          "eyeBrightnessHalflife",
          "centerDepthHalflife",
          "voxelDistance",
          "workGroups",
          "workGroupsRender",
          "generateShadowColorMipmap");

  public ShaderDirectives {
    drawBuffers = List.copyOf(drawBuffers);
    constants = Collections.unmodifiableMap(new TreeMap<>(constants));
  }

  public static ShaderDirectives parse(String preprocessedSource) throws ShaderPackException {
    List<Integer> targets = null;
    Matcher declaration = TARGETS.matcher(preprocessedSource);
    while (declaration.find()) {
      List<Integer> current = new ArrayList<>();
      String value = declaration.group(2).trim();
      try {
        if (declaration.group(1).equals("DRAWBUFFERS")) {
          if (!value.matches("[0-9N]*")) throw new NumberFormatException();
          for (char c : value.toCharArray()) current.add(c == 'N' ? -1 : c - '0');
        } else if (!value.isEmpty()) {
          for (String part : value.split(",", -1)) {
            int index = Integer.parseInt(part.trim());
            if (index < 0) throw new NumberFormatException();
            current.add(index);
          }
        }
      } catch (NumberFormatException e) {
        throw new ShaderPackException("Invalid " + declaration.group(1) + " declaration: " + value);
      }
      Set<Integer> used = new HashSet<>();
      for (int index : current) {
        if (index >= 0 && !used.add(index))
          throw new ShaderPackException("Duplicate render target " + index);
      }
      if (targets != null && !targets.equals(current)) {
        throw new ShaderPackException("Multiple conflicting active render-target directives");
      }
      targets = current;
    }
    Map<String, String> constants = new TreeMap<>();
    Matcher constant = CONSTANT.matcher(preprocessedSource);
    while (constant.find()) {
      String name = constant.group(1);
      if (!BUFFER.matcher(name).matches()
          && !GLOBAL.contains(name)
          && !name.matches(
              "shadow(?:HardwareFiltering[01]?|tex[01](?:Mipmap|Nearest)|[Cc]olor\\d+(?:Format|Mipmap|Nearest|Clear|ClearColor))")) {
        continue;
      }
      String value = constant.group(2).trim();
      String previous = constants.putIfAbsent(name, value);
      if (previous != null && !previous.equals(value)) {
        throw new ShaderPackException("Conflicting active shader directive " + name);
      }
    }
    return new ShaderDirectives(targets == null ? List.of(0) : targets, constants);
  }

  public Map<Integer, String> bufferFormats() throws ShaderPackException {
    return bufferValues("Format");
  }

  public Map<Integer, Boolean> bufferClear() throws ShaderPackException {
    Map<Integer, Boolean> result = new TreeMap<>();
    for (var entry : bufferValues("Clear").entrySet()) {
      result.put(entry.getKey(), booleanValue(entry.getValue()));
    }
    return Collections.unmodifiableMap(result);
  }

  public Map<Integer, List<Float>> bufferClearColors() throws ShaderPackException {
    Map<Integer, List<Float>> result = new TreeMap<>();
    for (var entry : bufferValues("ClearColor").entrySet()) {
      String value = entry.getValue();
      if (!value.matches("vec4\\s*\\([^()]+\\)"))
        throw new ShaderPackException("Unsupported clear color: " + value);
      String[] components =
          value.substring(value.indexOf('(') + 1, value.lastIndexOf(')')).split(",");
      if (components.length != 1 && components.length != 4)
        throw new ShaderPackException("Invalid clear color: " + value);
      List<Float> color = new ArrayList<>();
      for (int i = 0; i < 4; i++) color.add(floatValue(components[components.length == 1 ? 0 : i]));
      result.put(entry.getKey(), List.copyOf(color));
    }
    return Collections.unmodifiableMap(result);
  }

  private static final Pattern SHADOW_COLOR =
      Pattern.compile("shadow[Cc]olor(\\d+)(Format|Mipmap|Nearest|Clear|ClearColor)");

  /**
   * Cadence: the shadowcolorN directives (Format, Mipmap, Nearest, Clear, ClearColor), by buffer index, for one suffix.
   * OptiFine spells the mipmap one shadowColor0Mipmap and the others shadowcolor0...; both spellings are accepted.
   */
  public Map<Integer, String> shadowColorValues(String suffix) throws ShaderPackException {
    Map<Integer, String> result = new TreeMap<>();
    for (var entry : constants.entrySet()) {
      Matcher m = SHADOW_COLOR.matcher(entry.getKey());
      if (!m.matches() || !m.group(2).equals(suffix)) continue;
      String previous = result.putIfAbsent(Integer.parseInt(m.group(1)), entry.getValue());
      if (previous != null && !previous.equals(entry.getValue()))
        throw new ShaderPackException("Conflicting shadowcolor" + m.group(1) + suffix);
    }
    return Collections.unmodifiableMap(result);
  }

  public static boolean booleanDirective(String value) throws ShaderPackException {
    return booleanValue(value);
  }

  /** A vec4(...) clear colour directive as four floats. */
  public static List<Float> clearColorDirective(String value) throws ShaderPackException {
    if (!value.matches("vec4\\s*\\([^()]+\\)"))
      throw new ShaderPackException("Unsupported clear color: " + value);
    String[] components = value.substring(value.indexOf('(') + 1, value.lastIndexOf(')')).split(",");
    if (components.length != 1 && components.length != 4)
      throw new ShaderPackException("Invalid clear color: " + value);
    List<Float> color = new ArrayList<>();
    for (int i = 0; i < 4; i++) color.add(floatValue(components[components.length == 1 ? 0 : i]));
    return List.copyOf(color);
  }

  public Set<Integer> mipmapBuffers() throws ShaderPackException {
    Set<Integer> result = new HashSet<>();
    for (var entry : bufferValues("MipmapEnabled").entrySet()) {
      if (booleanValue(entry.getValue())) result.add(entry.getKey());
    }
    return Set.copyOf(result);
  }

  private Map<Integer, String> bufferValues(String suffix) throws ShaderPackException {
    Map<Integer, String> result = new TreeMap<>();
    for (var entry : constants.entrySet()) {
      Matcher buffer = BUFFER.matcher(entry.getKey());
      if (!buffer.matches() || !buffer.group(2).equals(suffix)) continue;
      int index = colorBufferIndex(buffer.group(1));
      String previous = result.putIfAbsent(index, entry.getValue());
      if (previous != null && !previous.equals(entry.getValue())) {
        throw new ShaderPackException("Conflicting aliases for colortex" + index + suffix);
      }
    }
    return Collections.unmodifiableMap(result);
  }

  public static int colorBufferIndex(String name) throws ShaderPackException {
    if (name.matches("colortex\\d+")) {
      try {
        return Integer.parseInt(name.substring(8));
      } catch (NumberFormatException e) {
        throw new ShaderPackException("Color buffer index is too large: " + name);
      }
    }
    return switch (name) {
      case "gcolor" -> 0;
      case "gdepth" -> 1;
      case "gnormal" -> 2;
      case "composite" -> 3;
      case "gaux1" -> 4;
      case "gaux2" -> 5;
      case "gaux3" -> 6;
      case "gaux4" -> 7;
      default -> throw new ShaderPackException("Not a color buffer: " + name);
    };
  }

  public int intConstant(String name, int fallback) throws ShaderPackException {
    String value = constants.get(name);
    if (value == null) return fallback;
    try {
      return Integer.parseInt(numericLiteral(value));
    } catch (NumberFormatException e) {
      throw new ShaderPackException("Expected integer directive " + name + ", found " + value);
    }
  }

  public float floatConstant(String name, float fallback) throws ShaderPackException {
    return constants.containsKey(name) ? floatValue(constants.get(name)) : fallback;
  }

  public boolean booleanConstant(String name, boolean fallback) throws ShaderPackException {
    return constants.containsKey(name) ? booleanValue(constants.get(name)) : fallback;
  }

  private static boolean booleanValue(String value) throws ShaderPackException {
    if (value.equals("true")) return true;
    if (value.equals("false")) return false;
    throw new ShaderPackException("Expected boolean directive, found " + value);
  }

  private static float floatValue(String value) throws ShaderPackException {
    try {
      float result = Float.parseFloat(numericLiteral(value));
      if (!Float.isFinite(result)) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException e) {
      throw new ShaderPackException("Expected finite float directive, found " + value);
    }
  }

  private static String numericLiteral(String value) {
    // shaderc emits separate tokens for a unary sign (for example "- 40.0"). Accept whitespace
    // between that sign and its literal while continuing to reject arbitrary constant expressions.
    return value.trim().replaceFirst("^([+-])\\s+", "$1");
  }
}
