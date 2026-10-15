package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Cadence: OptiFine/Iris blend overrides from shaders.properties. {@code blend.<program> = off | <src> <dst> <srcA> <dstA>}
 * sets every buffer a program writes; {@code blend.<program>.<buffer>} (colortexN or its old name, shadowcolorN) one buffer,
 * and wins over the program's. Without either, a program blends as before: world programs as the game's draw, screen passes
 * not at all. Pegasus switches blending off for the buffers that hold normals and material data (blended, a translucent
 * draw mixed them with what was behind it); I Like Vanilla and Bliss pick their own functions per buffer.
 */
public final class PackBlend {
  private static volatile Map<String, String> overrides = Map.of();

  private PackBlend() {
  }

  /** The active pack's overrides (every blend.* property), or none. */
  public static void configure(Map<String, String> properties) {
    Map<String, String> result = new HashMap<>();
    for (var e : properties.entrySet())
      if (e.getKey().startsWith("blend.")) result.put(e.getKey().substring(6).strip(), e.getValue().strip());
    overrides = Map.copyOf(result);
  }

  public static boolean isEmpty() {
    return overrides.isEmpty();
  }

  /** The blend for program's output to buffer (shadow: shadowcolor buffer), given what it would be without an override. */
  public static Optional<BlendFunction> resolve(String program, int buffer, boolean shadow, Optional<BlendFunction> fallback) {
    Map<String, String> map = overrides;
    if (map.isEmpty()) return fallback;
    String value = null;
    for (String name : bufferNames(buffer, shadow)) {
      value = map.get(program + "." + name);
      if (value != null) break;
    }
    if (value == null) value = map.get(program);
    if (value == null) return fallback;
    Optional<BlendFunction> parsed = parse(value);
    return parsed == null ? fallback : parsed;
  }

  private static String[] bufferNames(int buffer, boolean shadow) {
    if (shadow) return new String[] {"shadowcolor" + buffer, "colortex" + buffer};
    String old = switch (buffer) {
      case 0 -> "gcolor";
      case 1 -> "gdepth";
      case 2 -> "gnormal";
      case 3 -> "composite";
      case 4 -> "gaux1";
      case 5 -> "gaux2";
      case 6 -> "gaux3";
      case 7 -> "gaux4";
      default -> null;
    };
    return old == null ? new String[] {"colortex" + buffer} : new String[] {"colortex" + buffer, old};
  }

  /** "off" -> empty; four factors -> that function (add); anything else -> null (ignored, as Iris logs and ignores it). */
  static Optional<BlendFunction> parse(String value) {
    String v = value.strip();
    if (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false")) return Optional.empty();
    String[] f = v.split("\\s+");
    if (f.length != 4) return null;
    try {
      return Optional.of(new BlendFunction(factor(f[0]), factor(f[1]), factor(f[2]), factor(f[3])));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static BlendFactor factor(String name) {
    String n = name.toUpperCase(Locale.ROOT);
    if (n.startsWith("GL_")) n = n.substring(3);
    return BlendFactor.valueOf(n);
  }
}
