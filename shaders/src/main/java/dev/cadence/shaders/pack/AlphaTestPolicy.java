// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.pack;

import java.util.Objects;

/** Legacy host alpha testing takes place after the pack's fragment program produces its color. */
public record AlphaTestPolicy(Function function, float reference) {
  public enum Function {
    NEVER,
    LESS,
    EQUAL,
    LEQUAL,
    GREATER,
    NOTEQUAL,
    GEQUAL,
    ALWAYS
  }

  public static final AlphaTestPolicy OFF = new AlphaTestPolicy(Function.ALWAYS, 0);

  public AlphaTestPolicy {
    Objects.requireNonNull(function);
    if (!Float.isFinite(reference))
      throw new IllegalArgumentException("Alpha reference must be finite");
    // Match the legacy GL alpha-test reference range, including properties outside that range.
    reference = Math.clamp(reference, 0, 1);
  }

  public static AlphaTestPolicy greater(float reference) {
    return new AlphaTestPolicy(Function.GREATER, reference);
  }

  public static AlphaTestPolicy parse(String declaration, AlphaTestPolicy fallback)
      throws ShaderPackException {
    if (declaration == null || declaration.isBlank()) return Objects.requireNonNull(fallback);
    String value = declaration.trim();
    // Cadence: OptiFine also reads false/true (off / the default) and GL_-prefixed function names (Bliss writes false)
    if (value.equals("off") || value.equals("false")) return OFF;
    if (value.equals("true")) return Objects.requireNonNull(fallback);
    if (value.startsWith("GL_")) value = value.substring(3);
    String[] words = value.split("\\s+");
    if (words.length != 2)
      throw new ShaderPackException(
          "Expected alpha test '<function> <reference>' or 'off': " + declaration);
    try {
      Function function =
          words[0].equals("GL_ALWAYS") || words[0].equals("ALWAYS") ? Function.ALWAYS : Function.valueOf(words[0]);
      return new AlphaTestPolicy(function, Float.parseFloat(words[1]));
    } catch (IllegalArgumentException invalid) {
      throw new ShaderPackException(
          "Invalid alpha test: "
              + declaration
              + "; expected a GL comparison function and a finite reference",
          invalid);
    }
  }

  /**
   * ALWAYS/off must not be confused with GREATER 0, which still rejects fully transparent texels.
   */
  public boolean enabled() {
    return function != Function.ALWAYS;
  }

  /** The caller discards on !condition so unordered NaN comparisons retain GL semantics. */
  public String passCondition(String alphaExpression, String referenceExpression) {
    String operator =
        switch (function) {
          case LESS -> "<";
          case EQUAL -> "==";
          case LEQUAL -> "<=";
          case GREATER -> ">";
          case NOTEQUAL -> "!=";
          case GEQUAL -> ">=";
          case NEVER, ALWAYS -> null;
        };
    return operator == null
        ? function == Function.ALWAYS ? "true" : "false"
        : "(" + alphaExpression + ") " + operator + " (" + referenceExpression + ")";
  }
}
