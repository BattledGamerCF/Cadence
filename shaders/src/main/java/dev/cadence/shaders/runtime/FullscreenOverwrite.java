// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import dev.cadence.shaders.compile.TranslatedProgram;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative full-coverage proof for the loader's owned fullscreen-triangle execution path. */
public final class FullscreenOverwrite {
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z_]\\w*|[0-9]+|\\S");
  private static final Pattern OUTPUT =
      Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*([0-7])\\s*\\)\\s*out\\s+vec4\\s+(sl_FragData([0-7]))\\s*;");
  private static final Set<String> VERTEX_BUILTINS =
      Set.of("gl_Position", "gl_MultiTexCoord0", "gl_Color", "gl_Normal",
          "gl_ModelViewMatrix", "gl_ProjectionMatrix", "gl_ModelViewProjectionMatrix",
          "gl_NormalMatrix", "gl_TextureMatrix", "gl_VertexID", "gl_InstanceID");
  private static final Set<String> FRAGMENT_BUILTINS = Set.of("gl_FragCoord", "gl_FrontFacing");
  private static final Set<String> FORBIDDEN =
      Set.of("discard", "demote", "terminateInvocation", "buffer", "shared", "coherent", "volatile",
          "atomic_uint", "barrier", "memoryBarrier", "groupMemoryBarrier", "early_fragment_tests",
          "post_depth_coverage");

  private FullscreenOverwrite() {}

  public record Result(int colorMask, String reason) {
    public boolean eligible() { return colorMask != 0; }
  }

  /**
   * This proof alone does not grant discard: the caller must use execute()'s full render area,
   * three owned vertices, no scissor/depth/culling/blending, and complete color write masks.
   */
  public static Result analyze(TranslatedProgram program) {
    if (!program.attributes().isEmpty() || program.drawBuffers().isEmpty()
        || program.drawBuffers().size() > 8) return rejected("unsupported-output-interface");
    String vertex = clean(program.preprocessedVertex());
    String fragment = clean(program.fragmentSource());
    if (vertex == null || fragment == null) return rejected("unsupported-source-directive");
    List<String> v = tokens(vertex), f = tokens(fragment);
    for (String token : v) {
      if (FORBIDDEN.contains(token)
          || token.startsWith("sl_")
          || token.startsWith("gl_") && !VERTEX_BUILTINS.contains(token))
        return rejected("vertex-coverage-control");
    }
    for (String token : f) {
      if (FORBIDDEN.contains(token)
          || token.matches("(?:[iu]?image\\w*|atomic\\w*|memoryBarrier\\w*|subgroup\\w*|subpass\\w*|.*Invocation.*|helperInvocation.*|.*Interlock.*)")
          || token.startsWith("gl_") && !FRAGMENT_BUILTINS.contains(token))
        return rejected("fragment-coverage-control");
    }
    Body vertexMain = main(v), fragmentMain = main(f);
    if (vertexMain == null || fragmentMain == null) return rejected("unsupported-main");
    if (hasReturn(v, vertexMain) || hasReturn(f, fragmentMain)) return rejected("early-main-return");
    // The fullscreen adapter supplies (-1,-1),(3,-1),(-1,3), with w=1, and FrameUniforms
    // supplies identity fixed-function matrices. A single unconditional ftransform is exact.
    int position = v.indexOf("gl_Position");
    if (position < 0 || v.lastIndexOf("gl_Position") != position
        || !statementAtTopLevel(v, vertexMain, position)
        || !sequence(v, position, "gl_Position", "=", "ftransform", "(", ")", ";")
        || v.indexOf("ftransform") != v.lastIndexOf("ftransform"))
      return rejected("unproven-fullscreen-position");

    var declarations = OUTPUT.matcher(fragment);
    var declared = new HashSet<Integer>();
    StringBuilder executable = new StringBuilder(fragment);
    while (declarations.find()) {
      int location = Integer.parseInt(declarations.group(1));
      if (location != Integer.parseInt(declarations.group(3)) || !declared.add(location))
        return rejected("unsupported-output-interface");
      for (int i = declarations.start(); i < declarations.end(); i++) executable.setCharAt(i, ' ');
    }
    List<String> bodyTokens = tokens(executable.toString());
    Body body = main(bodyTokens);
    if (body == null) return rejected("unsupported-main");
    int mask = 0;
    var targets = new HashSet<Integer>();
    for (int location = 0; location < program.drawBuffers().size(); location++) {
      int target = program.drawBuffers().get(location);
      if (target < 0) continue;
      if (!targets.add(target)) return rejected("aliased-color-attachments");
      String output = "sl_FragData" + location;
      int at = bodyTokens.indexOf(output);
      // Exactly one whole-vector store, directly in main, excludes conditional/component writes,
      // helper aliases, output reads and compound updates that could depend on old attachment data.
      if (!declared.contains(location) || at < 0 || bodyTokens.lastIndexOf(output) != at
          || !statementAtTopLevel(bodyTokens, body, at)
          || !sequence(bodyTokens, at, output, "=")
          || sequence(bodyTokens, at, output, "=", "="))
        return rejected("unproven-output-coverage:" + location);
      mask |= 1 << location;
    }
    return new Result(mask, "owned-triangle-unconditional-color-stores");
  }

  private record Body(int open, int close) {}

  private static Body main(List<String> tokens) {
    Body found = null;
    for (int i = 0; i + 3 < tokens.size(); i++) {
      if (!sequence(tokens, i, "void", "main", "(")) continue;
      int end = i + 3;
      if (tokens.get(end).equals("void")) end++;
      if (!sequence(tokens, end, ")", "{")) continue;
      int open = end + 1, depth = 1;
      for (int j = open + 1; j < tokens.size(); j++) {
        if (tokens.get(j).equals("{")) depth++;
        if (tokens.get(j).equals("}")) depth--;
        if (depth == 0) {
          if (found != null) return null;
          found = new Body(open, j);
          break;
        }
      }
    }
    return found;
  }

  private static boolean hasReturn(List<String> tokens, Body body) {
    return tokens.subList(body.open + 1, body.close).contains("return");
  }

  private static boolean statementAtTopLevel(List<String> tokens, Body body, int at) {
    if (at <= body.open || at >= body.close) return false;
    int braces = 0, parentheses = 0;
    for (int i = body.open + 1; i < at; i++) {
      switch (tokens.get(i)) {
        case "{" -> braces++;
        case "}" -> braces--;
        case "(" -> parentheses++;
        case ")" -> parentheses--;
        default -> {}
      }
    }
    String previous = tokens.get(at - 1);
    return braces == 0 && parentheses == 0
        && (previous.equals(";") || previous.equals("{") || previous.equals("}"));
  }

  private static boolean sequence(List<String> tokens, int start, String... values) {
    if (start + values.length > tokens.size()) return false;
    for (int i = 0; i < values.length; i++) if (!tokens.get(start + i).equals(values[i])) return false;
    return true;
  }

  private static List<String> tokens(String text) {
    var result = new ArrayList<String>();
    var matcher = TOKEN.matcher(text);
    while (matcher.find()) result.add(matcher.group());
    return result;
  }

  private static String clean(String source) {
    char[] chars = source.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      if (chars[i] != '/' || i + 1 >= chars.length) continue;
      int end;
      if (chars[i + 1] == '/') {
        end = source.indexOf('\n', i + 2);
        if (end < 0) end = chars.length;
      } else if (chars[i + 1] == '*') {
        end = source.indexOf("*/", i + 2);
        if (end < 0) return null;
        end += 2;
      } else continue;
      for (; i < end; i++) if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ';
      i--;
    }
    StringBuilder lines = new StringBuilder();
    for (String line : new String(chars).split("\\R", -1)) {
      String trimmed = line.stripLeading();
      if (trimmed.startsWith("#")) {
        if (!trimmed.matches("#\\s*(?:version|line|extension)\\b.*")) return null;
        lines.append('\n');
      } else {
        if (line.indexOf('"') >= 0 || line.indexOf('\\') >= 0) return null;
        lines.append(line).append('\n');
      }
    }
    return lines.toString();
  }

  private static Result rejected(String reason) { return new Result(0, reason); }
}
