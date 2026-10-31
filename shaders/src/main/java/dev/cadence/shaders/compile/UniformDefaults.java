package dev.cadence.shaders.compile;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cadence: the value of a uniform's GLSL initializer (`uniform float x = 0.5;`, `uniform vec3 c = vec3(1.0, 0.5, 0.0);`),
 * which is the uniform's value until the host sets it. Literal values only; anything else is null (refused).
 */
final class UniformDefaults {
  private static final Pattern CONSTRUCTOR = Pattern.compile("([ibu]?vec[234]|float|int|uint|bool)\\s*\\((.*)\\)");

  private UniformDefaults() {
  }

  /** Components in order (bool as 1/0), or null when the initializer isn't a literal of a supported type. */
  static float[] parse(String type, String initializer) {
    if (!type.matches("[ibu]?vec[234]|float|int|uint|bool")) return null;
    int components = type.contains("vec") ? type.charAt(type.length() - 1) - '0' : 1;
    String[] parts;
    Matcher constructor = CONSTRUCTOR.matcher(initializer);
    if (constructor.matches()) parts = constructor.group(2).split(",");
    else parts = new String[] {initializer};
    float[] values = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      Float v = literal(parts[i].strip());
      if (v == null) return null;
      values[i] = v;
    }
    if (values.length == components) return values;
    if (values.length == 1) { // vec3(0.5): every component
      float[] splat = new float[components];
      java.util.Arrays.fill(splat, values[0]);
      return splat;
    }
    return null;
  }

  private static Float literal(String text) {
    if (text.equals("true")) return 1f;
    if (text.equals("false")) return 0f;
    // (a sign may be written apart from its number: "ivec3(- 98257195)", Rethinking Voxels)
    String number = text.replaceFirst("(?i)[fu]$", "").replaceFirst("^([+-])\\s+", "$1");
    try {
      return Float.parseFloat(number);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
