// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.compile;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Preserves Minecraft texture-channel contracts when replacing its material fragment shader. */
public final class MinecraftTextureAdapter {
  private static final Set<String> FETCHES =
      Set.of(
          "texture",
          "textureLod",
          "textureProj",
          "textureGrad",
          "textureOffset",
          "textureLodOffset",
          "textureProjLod",
          "textureProjOffset",
          "textureProjLodOffset",
          "textureGradOffset",
          "textureProjGrad",
          "textureProjGradOffset",
          "texelFetch",
          "texelFetchOffset");
  private static final Set<String> QUERIES =
      Set.of("textureSize", "textureQueryLevels", "textureQueryLod");
  private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");

  private MinecraftTextureAdapter() {}

  public static TranslatedProgram adapt(RenderPipeline original, TranslatedProgram program) {
    if (!original.getShaders().get(ShaderType.FRAGMENT).getPath().equals("core/text")
        || !original.getShaderDefines().flags().contains("IS_GRAYSCALE")) return program;
    Set<String> albedo = new HashSet<>();
    for (var sampler : program.samplers()) {
      if (Set.of("texture", "gtexture", "tex").contains(sampler.name()))
        albedo.add(ShaderCompatibilityCompiler.samplerShaderName(sampler.name()));
    }
    if (albedo.isEmpty()) return program;
    // Vanilla core/text.fsh treats red-only glyph coverage as RGBA via .rrrr. The pack must
    // see that same sample before its material arithmetic and host alpha test execute.
    return new TranslatedProgram(
        program.label() + "/grayscale_text",
        swizzleAlbedo(program.vertexSource(), albedo),
        swizzleAlbedo(program.fragmentSource(), albedo),
        program.uniforms(),
        program.samplers(),
        program.attributes(),
        program.drawBuffers(),
        program.preprocessedVertex(),
        program.preprocessedFragment(),
            program.storage());
  }

  private static String swizzleAlbedo(String source, Set<String> albedo) {
    // Mask comments without changing offsets. Work on tokens and balanced calls, not substrings:
    // an unrelated sampler and a nested coordinate expression must retain their semantics.
    char[] masked = source.toCharArray();
    var comments = COMMENTS.matcher(source);
    while (comments.find()) for (int i = comments.start(); i < comments.end(); i++) masked[i] = ' ';
    String code = new String(masked);
    var names =
        Pattern.compile(
                "\\b(?:" + String.join("|", albedo.stream().map(Pattern::quote).toList()) + ")\\b")
            .matcher(code);
    var edits = new ArrayList<Insertion>();
    while (names.find()) {
      String preceding = code.substring(Math.max(0, names.start() - 80), names.start());
      if (preceding.matches("(?s).*\\buniform\\s+sampler2D\\s+")) continue;
      int open = previousNonSpace(code, names.start() - 1);
      if (open < 0 || code.charAt(open) != '(')
        throw new UnsupportedOperationException(
            "Grayscale text requires a direct albedo fetch; unsupported use of " + names.group());
      int functionEnd = previousNonSpace(code, open - 1) + 1;
      int functionStart = functionEnd;
      while (functionStart > 0 && Character.isJavaIdentifierPart(code.charAt(functionStart - 1)))
        functionStart--;
      String function = code.substring(functionStart, functionEnd);
      if (QUERIES.contains(function)) continue;
      if (!FETCHES.contains(function))
        throw new UnsupportedOperationException(
            "Grayscale text albedo passed to unsupported function " + function);
      int close = open + 1, nesting = 1;
      for (; close < code.length() && nesting > 0; close++) {
        if (code.charAt(close) == '(') nesting++;
        else if (code.charAt(close) == ')') nesting--;
      }
      if (nesting != 0)
        throw new IllegalArgumentException("Unbalanced grayscale text texture fetch");
      edits.add(new Insertion(functionStart, "("));
      edits.add(new Insertion(close, ".rrrr)"));
    }
    edits.sort(Comparator.comparingInt(Insertion::offset).reversed());
    var result = new StringBuilder(source);
    for (var edit : edits) result.insert(edit.offset(), edit.text());
    return result.toString();
  }

  private static int previousNonSpace(String text, int index) {
    while (index >= 0 && Character.isWhitespace(text.charAt(index))) index--;
    return index;
  }

  private record Insertion(int offset, String text) {}
}
