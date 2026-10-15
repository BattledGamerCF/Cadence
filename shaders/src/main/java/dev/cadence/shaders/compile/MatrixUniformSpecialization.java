// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Exposes guarded zero matrix entries to the compiler without rewriting pack algorithms. */
public final class MatrixUniformSpecialization {
  private static final String PREFIX = "sl_sparse_matrix_";
  private static final Pattern TOKENS = Pattern.compile("[A-Za-z_]\\w*|[^\\s]");
  // Column-major inverse of the base perspective supplied by FrameUniforms. Dynamic entries
  // remain live even when they happen to be constant for one camera, including the near -1 term.
  public static final ZeroPattern PROJECTION_INVERSE =
      new ZeroPattern("gbufferProjectionInverse", 0x37de, 0x379e);

  private MatrixUniformSpecialization() {}

  public record ZeroPattern(String uniform, int zeroMask, int negativeZeroMask) {
    public ZeroPattern {
      if (!uniform.matches("[A-Za-z_]\\w*")
          || zeroMask == 0
          || (zeroMask & ~0xffff) != 0
          || (negativeZeroMask & ~zeroMask) != 0)
        throw new IllegalArgumentException("Invalid matrix zero pattern");
    }

    /** Exact zero signs and finite dynamic entries are part of the runtime specialization key. */
    public boolean matches(float[] values) {
      if (values == null || values.length != 16) return false;
      for (int index = 0; index < 16; index++) {
        if (!Float.isFinite(values[index])) return false;
        if ((zeroMask & (1 << index)) != 0
            && Float.floatToRawIntBits(values[index])
                != ((negativeZeroMask & (1 << index)) != 0 ? 0x80000000 : 0)) return false;
      }
      return true;
    }
  }

  public record Result(TranslatedProgram program, boolean applied, String reason) {}

  public static Result apply(TranslatedProgram original, ZeroPattern pattern) {
    var field =
        original.uniforms().fields().stream()
            .filter(value -> value.name().equals(pattern.uniform()))
            .findFirst()
            .orElse(null);
    if (field == null || !field.type().equals("mat4") || field.arrayLength() != 0)
      return new Result(original, false, "no-scalar-mat4-uniform");
    String block = original.uniforms().declaration();
    Stage vertex = rewrite(original.vertexSource(), block, pattern);
    Stage fragment = rewrite(original.fragmentSource(), block, pattern);
    if (vertex == null || fragment == null)
      return new Result(original, false, "ambiguous-uniform-scope");
    if (vertex.references + fragment.references == 0)
      return new Result(original, false, "unused-matrix");
    return new Result(
        new TranslatedProgram(
            original.label() + "/sparse_projection_inverse",
            vertex.source,
            fragment.source,
            original.uniforms(),
            original.samplers(),
            original.attributes(),
            original.drawBuffers(),
            original.preprocessedVertex(),
            original.preprocessedFragment(),
            original.storage()),
        true,
        "guarded-zero-entries");
  }

  private record Token(String text, int start, int end) {}

  private record Stage(String source, int references) {}

  private static Stage rewrite(String source, String uniformBlock, ZeroPattern pattern) {
    int blockStart = source.indexOf(uniformBlock);
    if (blockStart < 0
        || source.indexOf(uniformBlock, blockStart + 1) >= 0
        || source.contains(PREFIX)) return null;
    int blockEnd = blockStart + uniformBlock.length();
    // Own emitted UBO declaration is the only occurrence excluded from token substitution. Mask
    // comments without shifting source positions; preprocessed source contains no string literals.
    String masked = maskComments(source);
    if (masked == null) return null;
    var matcher = TOKENS.matcher(masked);
    List<Token> tokens = new ArrayList<>();
    while (matcher.find()) tokens.add(new Token(matcher.group(), matcher.start(), matcher.end()));
    List<Token> uses = new ArrayList<>();
    for (int index = 0; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      if (!token.text.equals(pattern.uniform())
          || token.start >= blockStart && token.end <= blockEnd) continue;
      String previous = index == 0 ? "" : tokens.get(index - 1).text;
      String next = index + 1 == tokens.size() ? "" : tokens.get(index + 1).text;
      // A member access, local/parameter declaration, function or preprocessor identifier might
      // shadow the UBO member. Reject the stage rather than guessing a lexical binding.
      if (previous.equals(".")
          || previous.matches("[A-Za-z_]\\w*") && !previous.equals("return")
          || next.equals("(")
          || token.start < blockEnd) return null;
      int line = masked.lastIndexOf('\n', token.start) + 1;
      if (masked.substring(line, token.start).stripLeading().startsWith("#")) return null;
      uses.add(token);
    }
    if (uses.isEmpty()) return new Stage(source, 0);
    String helper = PREFIX + pattern.uniform();
    StringBuilder result = new StringBuilder(source);
    for (int index = uses.size() - 1; index >= 0; index--) {
      Token use = uses.get(index);
      result.replace(use.start, use.end, helper + "()");
    }
    StringBuilder declaration =
        new StringBuilder("mat4 ").append(helper).append("() { return mat4(");
    for (int index = 0; index < 16; index++) {
      if (index != 0) declaration.append(", ");
      if ((pattern.zeroMask & (1 << index)) != 0)
        declaration.append((pattern.negativeZeroMask & (1 << index)) == 0 ? "0.0" : "-0.0");
      else
        declaration
            .append(pattern.uniform())
            .append('[')
            .append(index / 4)
            .append("][")
            .append(index % 4)
            .append(']');
    }
    declaration.append("); }\n");
    result.insert(blockEnd, declaration);
    return new Stage(result.toString(), uses.size());
  }

  private static String maskComments(String source) {
    char[] text = source.toCharArray();
    for (int i = 0; i < text.length; i++) {
      if (text[i] == '"' || text[i] == '\'') return null;
      if (text[i] != '/' || i + 1 == text.length) continue;
      if (text[i + 1] == '/') {
        while (i < text.length && text[i] != '\n') text[i++] = ' ';
      } else if (text[i + 1] == '*') {
        text[i++] = ' ';
        text[i++] = ' ';
        while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) {
          if (text[i] != '\n') text[i] = ' ';
          i++;
        }
        if (i + 1 >= text.length) return null;
        text[i] = text[i + 1] = ' ';
        i++;
      }
    }
    return new String(text);
  }
}
