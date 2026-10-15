// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import dev.cadence.shaders.compile.ShaderCompatibilityCompiler;
import dev.cadence.shaders.pack.ShaderDirectives;
import dev.cadence.shaders.pack.ShaderPackException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Proves requested lower mip levels dead without changing allocation or sampling state. */
public final class UnusedMipmaps {
  private static final Pattern COLOR =
      Pattern.compile("colortex[0-9]+|gcolor|gdepth|gnormal|composite|gaux[1-4]");
  private static final Pattern ZERO =
      Pattern.compile("(?:0+(?:\\.0*)?|\\.0+)(?:[eE][+-]?[0-9]+)?[fFuU]?");
  private final Map<String, Set<Integer>> skipped;

  private UnusedMipmaps(Map<String, Set<Integer>> skipped) {
    this.skipped = Map.copyOf(skipped);
  }

  public boolean skips(String program, int buffer) {
    return skipped.getOrDefault(program, Set.of()).contains(buffer);
  }

  public Map<String, Set<Integer>> skipped() {
    return skipped;
  }

  /** Build once per dimension/options graph, after conditional compilation and program gates. */
  public static UnusedMipmaps analyze(PackPrograms programs) throws ShaderPackException {
    List<Program> inputs = new ArrayList<>();
    for (var declared : programs.pack.programs(programs.directory)) {
      var source = programs.find(declared.name());
      if (source == null) continue;
      var translated = source.translated();
      inputs.add(
          new Program(
              source.name(),
              translated.vertexSource(),
              translated.fragmentSource(),
              translated.samplers().stream().map(s -> s.name()).toList(),
              Set.copyOf(translated.drawBuffers()),
              source.directives().mipmapBuffers()));
    }
    return analyze(inputs, programs.globals.bufferClear());
  }

  /** Package-visible immutable inputs make lifetime proofs testable without a GPU or game loop. */
  record Program(
      String name,
      String vertex,
      String fragment,
      List<String> samplers,
      Set<Integer> outputs,
      Set<Integer> requested) {
    Program {
      samplers = List.copyOf(samplers);
      outputs = Set.copyOf(outputs);
      requested = Set.copyOf(requested);
    }
  }

  private record Step(
      String name,
      Map<Integer, Boolean> reads,
      Set<Integer> outputs,
      Set<Integer> requested,
      boolean flips,
      boolean optional,
      boolean candidate,
      boolean clear) {}

  static UnusedMipmaps analyze(List<Program> programs, Map<Integer, Boolean> clear) {
    // New stage families or alternate lifetime semantics require an explicit model first.
    if (programs.stream()
            .anyMatch(
                p ->
                    !p.name()
                        .matches(
                            "(?:begin|prepare|deferred|composite)[0-9]*|final|shadow|gbuffers_[a-z0-9_]+"))
        || !uniqueStageOrder(programs)) return new UnusedMipmaps(Map.of());
    List<Step> steps = new ArrayList<>();
    steps.add(new Step("frame.clear", Map.of(), Set.of(), Set.of(), false, false, false, true));
    appendPhase(steps, programs, "begin", false);
    // The runtime draws this extra fullscreen sky background before shadow/scene geometry.
    programs.stream()
        .filter(p -> p.name().equals("gbuffers_skybasic"))
        .findFirst()
        .ifPresent(p -> steps.add(step(p, true, false, false)));
    programs.stream()
        .filter(p -> p.name().equals("shadow"))
        .forEach(p -> steps.add(step(p, false, true, false)));
    // prepare runs from endShadow(), which can be skipped if the view area is unavailable.
    // A potentially skipped flip invalidates the physical-side proof for that buffer.
    appendPhase(steps, programs, "prepare", true);
    appendGeometry(steps, programs);
    appendPhase(steps, programs, "deferred", false);
    appendGeometry(steps, programs);
    appendPhase(steps, programs, "composite", false);
    programs.stream()
        .filter(p -> p.name().equals("final"))
        .forEach(p -> steps.add(step(p, false, false, true)));
    Map<String, Set<Integer>> removed = new LinkedHashMap<>();
    for (int i = 0; i < steps.size(); i++) {
      Step candidate = steps.get(i);
      if (!candidate.candidate()) continue;
      for (int buffer : candidate.requested()) {
        if (dead(steps, i, buffer, 0, clear) && dead(steps, i, buffer, 1, clear))
          removed.computeIfAbsent(candidate.name(), ignored -> new HashSet<>()).add(buffer);
      }
    }
    removed.replaceAll((name, indices) -> Set.copyOf(indices));
    return new UnusedMipmaps(removed);
  }

  private static boolean uniqueStageOrder(List<Program> programs) {
    for (String phase : List.of("begin", "prepare", "deferred", "composite")) {
      Set<Integer> ordinals = new HashSet<>();
      for (Program program : programs) {
        if (!program.name().matches(phase + "[0-9]*")) continue;
        try {
          int ordinal =
              program.name().equals(phase)
                  ? 0
                  : Integer.parseInt(program.name().substring(phase.length()));
          // The runtime comparator does not distinguish composite from composite0 (or 1/01).
          if (!ordinals.add(ordinal)) return false;
        } catch (NumberFormatException unknownOrder) {
          return false;
        }
      }
    }
    return true;
  }

  private static void appendPhase(
      List<Step> steps, List<Program> programs, String prefix, boolean optional) {
    programs.stream()
        .filter(p -> p.name().matches(prefix + "[0-9]*"))
        .sorted(
            Comparator.comparingInt(
                p ->
                    p.name().equals(prefix)
                        ? 0
                        : Integer.parseInt(p.name().substring(prefix.length()))))
        .forEach(p -> steps.add(step(p, true, optional, !optional)));
  }

  private static void appendGeometry(List<Step> steps, List<Program> programs) {
    // Include every possible geometry consumer on both sides of deferred. Draw presence/order
    // is dynamic; geometry does not flip color pairs, and no optional generation proves a kill.
    programs.stream()
        .filter(p -> p.name().startsWith("gbuffers_"))
        .forEach(p -> steps.add(step(p, false, true, false)));
  }

  private static Step step(Program program, boolean flips, boolean optional, boolean candidate) {
    Map<Integer, Boolean> reads = new HashMap<>();
    for (String sampler : program.samplers()) {
      if (!COLOR.matcher(sampler).matches()) continue;
      try {
        int index = ShaderDirectives.colorBufferIndex(sampler);
        String shaderName = ShaderCompatibilityCompiler.samplerShaderName(sampler);
        boolean baseOnly =
            baseLevelOnly(program.vertex(), shaderName)
                && baseLevelOnly(program.fragment(), shaderName);
        reads.merge(index, baseOnly, (a, b) -> a && b);
      } catch (ShaderPackException malformed) {
        throw new IllegalArgumentException(malformed);
      }
    }
    return new Step(
        program.name(),
        Map.copyOf(reads),
        program.outputs(),
        program.requested(),
        flips,
        optional,
        candidate,
        false);
  }

  private static boolean dead(
      List<Step> steps, int origin, int buffer, int current, Map<Integer, Boolean> clear) {
    int physical = current;
    Set<Integer> visited = new HashSet<>();
    for (int distance = 0; ; distance++) {
      int index = (origin + distance) % steps.size();
      Step step = steps.get(index);
      if (step.clear()) {
        if (current == physical && clear.getOrDefault(buffer, true)) return true;
      } else {
        // Generation replaces every lower level. A level-zero render output does not.
        if (distance > 0
            && !step.optional()
            && current == physical
            && step.requested().contains(buffer)) return true;
        if (current == physical && !step.reads().getOrDefault(buffer, true)) return false;
        if (step.flips() && step.outputs().contains(buffer)) {
          if (step.optional()) return false;
          current ^= 1;
        }
      }
      // At most two frame parities are reachable. Unknown/unkilled repeated lifetimes stay eager.
      if (!visited.add(index * 2 + current)) return false;
    }
  }

  /**
   * Deliberately limited proof on compiler-produced GLSL, not a C/GLSL parser. Every use of the
   * named sampler must be its simple uniform declaration or a direct explicit-LOD-zero fetch.
   * Helpers, aliases through parameters, arrays, gradients, implicit LOD and unknown syntax fail
   * closed. Coordinates may contain arbitrarily nested expressions and texture calls.
   */
  static boolean baseLevelOnly(String source, String sampler) {
    List<String> tokens = tokenize(source);
    if (tokens == null) return false;
    // A user function can overload a builtin name. Do not infer its behavior from that name.
    for (int i = 0; i + 1 < tokens.size(); i++) {
      if (!Set.of("textureLod", "texelFetch").contains(tokens.get(i))
          || !tokens.get(i + 1).equals("(")) continue;
      int end = matching(tokens, i + 1);
      if (end < 0 || (end + 1 < tokens.size() && tokens.get(end + 1).equals("{"))) return false;
    }
    for (int i = 0; i < tokens.size(); i++) {
      if (!tokens.get(i).equals(sampler)) continue;
      if (i >= 2
          && i + 1 < tokens.size()
          && tokens.get(i - 2).equals("uniform")
          && tokens.get(i - 1).equals("sampler2D")
          && tokens.get(i + 1).equals(";")) continue;
      if (i < 2
          || !tokens.get(i - 1).equals("(")
          || !Set.of("textureLod", "texelFetch").contains(tokens.get(i - 2))) return false;
      int end = matching(tokens, i - 1);
      if (end < 0) return false;
      List<List<String>> arguments = arguments(tokens, i, end);
      if (arguments.size() != 3
          || !arguments.getFirst().equals(List.of(sampler))
          || arguments.get(2).size() != 1
          || !ZERO.matcher(arguments.get(2).getFirst()).matches()) return false;
    }
    return true;
  }

  private static int matching(List<String> tokens, int start) {
    int depth = 0;
    for (int i = start; i < tokens.size(); i++) {
      if (tokens.get(i).equals("(")) depth++;
      else if (tokens.get(i).equals(")") && --depth == 0) return i;
    }
    return -1;
  }

  private static List<List<String>> arguments(List<String> tokens, int start, int end) {
    List<List<String>> result = new ArrayList<>();
    int depth = 0, from = start;
    for (int i = start; i < end; i++) {
      String token = tokens.get(i);
      if (token.equals("(") || token.equals("[")) depth++;
      else if (token.equals(")") || token.equals("]")) depth--;
      else if (token.equals(",") && depth == 0) {
        result.add(tokens.subList(from, i));
        from = i + 1;
      }
    }
    result.add(tokens.subList(from, end));
    return result;
  }

  private static List<String> tokenize(String source) {
    List<String> result = new ArrayList<>();
    for (int i = 0; i < source.length(); ) {
      char c = source.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (source.startsWith("//", i)) {
        int end = source.indexOf('\n', i + 2);
        i = end < 0 ? source.length() : end + 1;
      } else if (source.startsWith("/*", i)) {
        int end = source.indexOf("*/", i + 2);
        if (end < 0) return null;
        i = end + 2;
      } else if (c == '"' || c == '\\') {
        return null;
      } else if (Character.isLetter(c) || c == '_') {
        int start = i++;
        while (i < source.length()
            && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) i++;
        result.add(source.substring(start, i));
      } else if (Character.isDigit(c)
          || (c == '.' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)))) {
        int start = i++;
        while (i < source.length()) {
          char n = source.charAt(i), previous = source.charAt(i - 1);
          if (Character.isLetterOrDigit(n)
              || n == '.'
              || ((n == '+' || n == '-') && (previous == 'e' || previous == 'E'))) i++;
          else break;
        }
        result.add(source.substring(start, i));
      } else {
        result.add(String.valueOf(c));
        i++;
      }
    }
    return result;
  }
}
