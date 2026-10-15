// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.compile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Moves immutable, uniform-only global initializers to flat fullscreen vertex outputs. This is an
 * expression transfer, not an algebraic rewrite: the original operation/dependency order and float
 * types are retained. Unknown syntax, functions, side effects and interpolated inputs stay put.
 */
public final class UniformInitializerLifting {
  private static final String PREFIX = "sl_lift_";
  private static final int MAX_OUTPUTS = 6;
  // A flat input has a cost too. Tiny arithmetic chains rarely amortize that cost and can differ
  // from fragment-only strength reduction (for example division by a constant versus reciprocal).
  private static final int MIN_EXPRESSION_COST = 8;
  private static final Set<String> TYPES =
      Set.of(
          "float", "int", "uint", "bool", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4",
          "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4");
  private static final Set<String> PURE =
      Set.of(
          "abs",
          "sign",
          "floor",
          "trunc",
          "round",
          "roundEven",
          "ceil",
          "fract",
          "mod",
          "min",
          "max",
          "clamp",
          "mix",
          "step",
          "smoothstep",
          "sqrt",
          "inversesqrt",
          "pow",
          "exp",
          "exp2",
          "log",
          "log2",
          "sin",
          "cos",
          "tan",
          "asin",
          "acos",
          "atan",
          "sinh",
          "cosh",
          "tanh",
          "asinh",
          "acosh",
          "atanh",
          "radians",
          "degrees",
          "length",
          "distance",
          "dot",
          "cross",
          "normalize",
          "faceforward",
          "reflect",
          "refract",
          "matrixCompMult",
          "outerProduct",
          "transpose",
          "determinant",
          "inverse",
          "lessThan",
          "lessThanEqual",
          "greaterThan",
          "greaterThanEqual",
          "equal",
          "notEqual",
          "any",
          "all",
          "not");
  private static final Pattern TOKENS =
      Pattern.compile(
          "[A-Za-z_]\\w*|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fFuU]?|\\+\\+|--|<<=|>>=|[+*/%&|^<>-]?=|&&|\\|\\||[^\\s]");
  private static final Pattern DECLARATION =
      Pattern.compile(
          "\\s*(const\\s+)?(float|int|uint|bool|[iub]?vec[234]|mat[234])\\s+([A-Za-z_]\\w*)\\s*(\\[\\s*[0-9]+\\s*])?\\s*=\\s*(.*?)\\s*;\\s*",
          Pattern.DOTALL);
  private static final Pattern INPUT =
      Pattern.compile(
          "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*(?:flat\\s+|smooth\\s+|noperspective\\s+|centroid\\s+|sample\\s+)*in\\s+(\\w+)\\s+\\w+\\s*(?:\\[\\s*(\\d+)\\s*])?\\s*;");
  private static final Pattern MAIN =
      Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");

  public record Result(TranslatedProgram program, List<String> lifted, int expressionOperations) {
    public boolean applied() {
      return !lifted.isEmpty();
    }
  }

  private record Token(String text, int start, int end) {}

  private record Global(
      String type,
      String name,
      String array,
      String expression,
      int start,
      int end,
      int expressionStart,
      int expressionEnd,
      boolean constant) {}

  private record Value(Global global, Set<String> dependencies, boolean uniform, int operations) {}

  private UniformInitializerLifting() {}

  /**
   * Call only for a fullscreen triangle: increasing geometry vertex work is a separate decision.
   */
  public static Result apply(TranslatedProgram source) {
    Result unchanged = new Result(source, List.of(), 0);
    String fragment = source.fragmentSource(), vertex = source.vertexSource();
    if (fragment.contains(PREFIX) || vertex.contains(PREFIX)) return unchanged;
    String clean = clean(fragment);
    List<Token> tokens = tokens(clean);
    List<Global> globals = globals(clean);
    Set<String> uniforms = new HashSet<>();
    source.uniforms().fields().forEach(field -> uniforms.add(field.name()));
    Map<String, Boolean> functions = functions(tokens);
    Map<String, Value> values = new LinkedHashMap<>();
    for (Global global : globals) {
      if (written(global, tokens, functions)) continue;
      Set<String> dependencies = new LinkedHashSet<>();
      boolean uniform = false, valid = true;
      int operations = 0;
      List<Token> expression = tokens(global.expression);
      for (int index = 0; index < expression.size(); index++) {
        String name = expression.get(index).text;
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
          if (Set.of("=", "+=", "-=", "*=", "/=", "%=", "++", "--").contains(name)) valid = false;
          if (Set.of("+", "-", "*", "/", "%").contains(name)) operations++;
          continue;
        }
        if (index > 0 && expression.get(index - 1).text.equals(".")) continue;
        if (name.equals("true") || name.equals("false") || TYPES.contains(name)) continue;
        if (index + 1 < expression.size() && expression.get(index + 1).text.equals("(")) {
          if (!PURE.contains(name) || functions.containsKey(name)) valid = false;
          operations +=
              Set.of("pow", "exp", "exp2", "log", "log2", "sin", "cos", "inverse").contains(name)
                  ? 8
                  : 2;
        } else if (uniforms.contains(name)) uniform = true;
        else {
          Value dependency = values.get(name);
          if (dependency == null) valid = false;
          else {
            dependencies.add(name);
            uniform |= dependency.uniform;
          }
        }
      }
      if (valid) values.put(global.name, new Value(global, dependencies, uniform, operations));
    }
    // Roots are values actually consumed beyond another transferable initializer. Intermediates
    // remain ordinary globals; dead-code elimination drops them when their last use moves away.
    List<Value> candidates = new ArrayList<>();
    for (Value value : values.values()) {
      Global global = value.global;
      if (!value.uniform
          || global.array != null
          || !global.type.matches("float|int|uint|[iu]?vec[234]")) continue;
      boolean outside = false;
      for (Token token : tokens) {
        if (!token.text.equals(global.name)
            || token.start >= global.start && token.end <= global.end) continue;
        boolean initializer = false;
        for (Value other : values.values()) {
          if (token.start >= other.global.expressionStart
              && token.end <= other.global.expressionEnd) {
            initializer = true;
            break;
          }
        }
        if (!initializer) {
          outside = true;
          break;
        }
      }
      if (outside && cost(value, values, new HashSet<>()) >= MIN_EXPRESSION_COST)
        candidates.add(value);
    }
    candidates.sort(
        Comparator.<Value>comparingInt(value -> cost(value, values, new HashSet<>())).reversed());
    if (candidates.isEmpty()) return unchanged;
    List<Value> roots =
        List.copyOf(candidates.subList(0, Math.min(MAX_OUTPUTS, candidates.size())));
    int location = 0;
    var inputs = INPUT.matcher(clean);
    while (inputs.find()) {
      int width =
          inputs.group(2).startsWith("mat") ? Integer.parseInt(inputs.group(2).substring(3)) : 1;
      int count = inputs.group(3) == null ? 1 : Integer.parseInt(inputs.group(3));
      location = Math.max(location, Integer.parseInt(inputs.group(1)) + width * count);
    }
    if (location + roots.size() > 24) return unchanged;
    Set<String> needed = new LinkedHashSet<>();
    for (Value root : roots) collect(root, values, needed);
    StringBuilder vDeclarations = new StringBuilder(),
        fDeclarations = new StringBuilder(),
        assignments = new StringBuilder();
    for (Value value : values.values()) {
      if (!needed.contains(value.global.name)) continue;
      Global global = value.global;
      vDeclarations
          .append(global.constant ? "const " : "")
          .append(global.type)
          .append(' ')
          .append(PREFIX)
          .append(global.name)
          .append(global.array == null ? "" : global.array)
          .append(" = ")
          .append(rename(global.expression, needed))
          .append(";\n");
    }
    Map<Integer, Value> replacements = new HashMap<>();
    for (Value root : roots) {
      Global global = root.global;
      String bridge = PREFIX + "value_" + global.name;
      String declaration = "layout(location=" + location++ + ") flat ";
      vDeclarations
          .append(declaration)
          .append("out ")
          .append(global.type)
          .append(' ')
          .append(bridge)
          .append(";\n");
      fDeclarations
          .append(declaration)
          .append("in ")
          .append(global.type)
          .append(' ')
          .append(bridge)
          .append(";\n");
      assignments.append(bridge).append(" = ").append(PREFIX).append(global.name).append(";\n");
      replacements.put(global.expressionStart, root);
    }
    StringBuilder replaced = new StringBuilder();
    int cursor = 0;
    for (Global global : globals) {
      if (!replacements.containsKey(global.expressionStart)) continue;
      replaced
          .append(fragment, cursor, global.expressionStart)
          .append(PREFIX)
          .append("value_")
          .append(global.name);
      cursor = global.expressionEnd;
    }
    replaced.append(fragment.substring(cursor));
    var main = MAIN.matcher(clean(vertex));
    if (!main.find()) return unchanged;
    int entry = main.start(), brace = main.end() - 1, end = closingBrace(clean(vertex), brace);
    if (end < 0 || main.find()) return unchanged;
    String liftedVertex =
        vertex.substring(0, entry)
            + vDeclarations
            + vertex.substring(entry, end)
            + assignments
            + vertex.substring(end);
    int versionEnd = replaced.indexOf("\n") + 1;
    if (versionEnd <= 0) return unchanged;
    String liftedFragment =
        replaced.substring(0, versionEnd) + fDeclarations + replaced.substring(versionEnd);
    int operations = 0;
    for (String name : needed)
      if (values.get(name).uniform) operations += values.get(name).operations;
    return new Result(
        new TranslatedProgram(
            source.label() + "/uniform_lifted",
            liftedVertex,
            liftedFragment,
            source.uniforms(),
            source.samplers(),
            source.attributes(),
            source.drawBuffers(),
            source.preprocessedVertex(),
            source.preprocessedFragment(),
            source.storage()),
        roots.stream().map(value -> value.global.name).toList(),
        operations);
  }

  private static int cost(Value value, Map<String, Value> values, Set<String> seen) {
    if (!seen.add(value.global.name) || !value.uniform) return 0;
    int cost = value.operations;
    for (String dependency : value.dependencies) cost += cost(values.get(dependency), values, seen);
    return cost;
  }

  private static void collect(Value value, Map<String, Value> values, Set<String> names) {
    if (!names.add(value.global.name)) return;
    for (String dependency : value.dependencies) collect(values.get(dependency), values, names);
  }

  private static String rename(String expression, Set<String> names) {
    StringBuilder result = new StringBuilder();
    int cursor = 0;
    String previous = "";
    for (Token token : tokens(expression)) {
      if (names.contains(token.text) && !previous.equals(".")) {
        result.append(expression, cursor, token.start).append(PREFIX).append(token.text);
        cursor = token.end;
      }
      previous = token.text;
    }
    return result.append(expression.substring(cursor)).toString();
  }

  private static List<Global> globals(String source) {
    List<Global> result = new ArrayList<>();
    int depth = 0, start = source.indexOf('\n') + 1;
    for (int index = start; index < source.length(); index++) {
      char c = source.charAt(index);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) start = index + 1;
      } else if (c == ';' && depth == 0) {
        var match = DECLARATION.matcher(source.substring(start, index + 1));
        if (match.matches())
          result.add(
              new Global(
                  match.group(2),
                  match.group(3),
                  match.group(4),
                  match.group(5),
                  start,
                  index + 1,
                  start + match.start(5),
                  start + match.end(5),
                  match.group(1) != null));
        start = index + 1;
      }
    }
    return result;
  }

  private static boolean written(
      Global global, List<Token> tokens, Map<String, Boolean> functions) {
    for (int index = 0; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      if (!token.text.equals(global.name) || token.start >= global.start && token.end <= global.end)
        continue;
      // A shadowing declaration is harmless at the original use site, but an initializer using
      // that name inside another scope is not part of this global-only transfer.
      if (index > 0 && Set.of("++", "--").contains(tokens.get(index - 1).text)) return true;
      int next = index + 1;
      while (next < tokens.size()) {
        if (tokens.get(next).text.equals(".") && next + 1 < tokens.size()) next += 2;
        else if (tokens.get(next).text.equals("[")) {
          int depth = 1;
          for (next++; next < tokens.size() && depth > 0; next++) {
            if (tokens.get(next).text.equals("[")) depth++;
            if (tokens.get(next).text.equals("]")) depth--;
          }
        } else break;
      }
      if (next < tokens.size()
          && Set.of("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", "++", "--")
              .contains(tokens.get(next).text)) return true;
    }
    // Out/inout calls may mutate a global without an assignment token. Reject that name whenever
    // it occurs as an argument to an unknown function; pure builtin calls have no output arguments.
    List<Boolean> calls = new ArrayList<>();
    for (int index = 0; index < tokens.size(); index++) {
      String text = tokens.get(index).text;
      if (text.equals("(")) {
        String previous = index == 0 ? "" : tokens.get(index - 1).text;
        boolean declaration =
            index > 1
                && (TYPES.contains(tokens.get(index - 2).text)
                    || tokens.get(index - 2).text.equals("void"));
        calls.add(
            !declaration
                && previous.matches("[A-Za-z_]\\w*")
                && !TYPES.contains(previous)
                && !(PURE.contains(previous) && !functions.containsKey(previous))
                && !Boolean.FALSE.equals(functions.get(previous))
                && !Set.of("if", "for", "while", "switch").contains(previous));
      } else if (text.equals(")")) {
        if (!calls.isEmpty()) calls.removeLast();
      } else if (text.equals(global.name) && calls.contains(true)) return true;
    }
    return false;
  }

  private static Map<String, Boolean> functions(List<Token> tokens) {
    Map<String, Boolean> result = new HashMap<>();
    for (int index = 1; index + 1 < tokens.size(); index++) {
      if ((TYPES.contains(tokens.get(index - 1).text) || tokens.get(index - 1).text.equals("void"))
          && tokens.get(index + 1).text.equals("(")) {
        boolean writes = false;
        for (int next = index + 2;
            next < tokens.size() && !tokens.get(next).text.equals(")");
            next++) writes |= Set.of("out", "inout").contains(tokens.get(next).text);
        result.merge(tokens.get(index).text, writes, (a, b) -> a || b);
      }
    }
    return result;
  }

  private static List<Token> tokens(String source) {
    List<Token> result = new ArrayList<>();
    var matcher = TOKENS.matcher(source);
    while (matcher.find()) result.add(new Token(matcher.group(), matcher.start(), matcher.end()));
    return result;
  }

  private static String clean(String source) {
    return Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*|(?m)^\\s*#[^\\r\\n]*")
        .matcher(source)
        .replaceAll(match -> match.group().replaceAll("[^\\r\\n]", " "));
  }

  private static int closingBrace(String source, int start) {
    int depth = 0;
    for (int index = start; index < source.length(); index++) {
      if (source.charAt(index) == '{') depth++;
      else if (source.charAt(index) == '}' && --depth == 0) return index;
    }
    return -1;
  }
}
