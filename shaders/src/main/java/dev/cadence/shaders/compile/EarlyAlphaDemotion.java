// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.compile;

import dev.cadence.shaders.pack.AlphaTestPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** A deliberately narrow alpha-stability proof; unsupported syntax retains the final test only. */
final class EarlyAlphaDemotion {
  private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z_]\\w*|\\S");
  private static final Set<String> FORBIDDEN =
      Set.of(
          "discard",
          "demote",
          "buffer",
          "shared",
          "coherent",
          "volatile",
          "atomic_uint",
          "gl_HelperInvocation",
          "gl_FragDepth",
          "gl_SampleMask",
          "gl_SampleMaskIn",
          "barrier",
          "memoryBarrier",
          "groupMemoryBarrier");

  record Result(String source, boolean applied, String reason) {}

  private record Token(String text, int end) {}

  private EarlyAlphaDemotion() {}

  static Result apply(String source, AlphaTestPolicy policy) {
    if (!policy.enabled()) return unchanged(source, "alpha-test-disabled");
    char[] masked = source.toCharArray();
    var comments = COMMENTS.matcher(source);
    while (comments.find()) for (int i = comments.start(); i < comments.end(); i++) masked[i] = ' ';
    var matcher = TOKEN.matcher(new String(masked));
    List<Token> tokens = new ArrayList<>();
    while (matcher.find()) tokens.add(new Token(matcher.group(), matcher.end()));
    int main = -1, output = -1;
    for (int i = 0; i < tokens.size(); i++) {
      String token = text(tokens, i);
      if (FORBIDDEN.contains(token)
          || token.matches(
              "(?:[iu]?image\\w*|atomic\\w*|memoryBarrier\\w*|subgroup\\w*|quad\\w*|.*Invocation.*|helperInvocation.*|clock\\w*|.*[Bb]allot.*|.*[Ss]huffle.*|.*[Bb]roadcast.*)"))
        return unchanged(source, "observable-effect-or-invocation-control");
      if (token.startsWith("sl_FragData")) {
        if (!token.equals("sl_FragData0") || output != -1)
          return unchanged(source, "multiple-output-uses");
        output = i;
      }
      if (token.equals("void")
          && text(tokens, i + 1).equals("main")
          && text(tokens, i + 2).equals("(")) {
        int close = i + (text(tokens, i + 3).equals("void") ? 4 : 3);
        if (!text(tokens, close).equals(")") || !text(tokens, close + 1).equals("{") || main != -1)
          return unchanged(source, "unsupported-main");
        main = close + 1;
      }
    }
    if (main < 0) return unchanged(source, "missing-main");
    int end = -1, depth = 1;
    for (int i = main + 1; i < tokens.size(); i++) {
      if (text(tokens, i).equals("{")) depth++;
      if (text(tokens, i).equals("}") && --depth == 0) {
        end = i;
        break;
      }
    }
    if (end < 0
        || output != end - 4
        || !text(tokens, output + 1).equals("=")
        || !text(tokens, output + 3).equals(";"))
      return unchanged(source, "output-not-a-terminal-copy");
    String candidate = text(tokens, output + 2);
    if (candidate.equals("alphaTestRef")) return unchanged(source, "threshold-name-shadowed");
    if (!candidate.matches("[A-Za-z_]\\w*")
        || !text(tokens, main + 1).equals("vec4")
        || !text(tokens, main + 2).equals(candidate)
        || !text(tokens, main + 3).equals("="))
      return unchanged(source, "first-statement-not-output-initializer");
    int initializerEnd = -1, parentheses = 0, brackets = 0;
    for (int i = main + 4; i < output; i++) {
      String token = text(tokens, i);
      if (token.equals("{") || token.equals("}")) return unchanged(source, "complex-initializer");
      if (token.equals("(")) parentheses++;
      if (token.equals(")")) parentheses--;
      if (token.equals("[")) brackets++;
      if (token.equals("]")) brackets--;
      if (parentheses < 0 || brackets < 0) return unchanged(source, "unbalanced-initializer");
      if (token.equals(";") && parentheses == 0 && brackets == 0) {
        initializerEnd = i;
        break;
      }
    }
    if (initializerEnd < 0) return unchanged(source, "missing-initializer");
    for (int i = initializerEnd + 1; i < output; i++) {
      if (text(tokens, i).equals("return")) return unchanged(source, "early-return");
      if (!text(tokens, i).equals(candidate)) continue;
      // GLSL RGB/XYZ swizzle values and copy-out arguments cannot alias the fourth component.
      // Reject whole-vector aliases, alpha reads/writes, dynamic indexing and nested swizzles.
      if (!text(tokens, i + 1).equals(".")
          || !text(tokens, i + 2).matches("[rgbxyz]{1,3}")
          || Set.of("[", ".").contains(text(tokens, i + 3)))
        return unchanged(source, "alpha-or-whole-vector-use");
    }
    int insertion = tokens.get(initializerEnd).end;
    String statement =
        "\nif (!(" + policy.passCondition(candidate + ".a", "alphaTestRef") + ")) demote;\n";
    return new Result(
        source.substring(0, insertion) + statement + source.substring(insertion),
        true,
        "stable-fourth-component");
  }

  private static String text(List<Token> tokens, int index) {
    return index >= 0 && index < tokens.size() ? tokens.get(index).text : "";
  }

  private static Result unchanged(String source, String reason) {
    return new Result(source, false, reason);
  }
}
