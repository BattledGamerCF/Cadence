package dev.cadence.shaders.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cadence: a compute shader's shared vec3 arrays, as three float arrays. Metal stores a threadgroup float3 in 16 bytes where
 * desktop drivers use 12, and Apple GPUs allow 32 KB of threadgroup memory: Photon's sky spherical harmonics
 * (`shared vec3 shared_memory[256][9]`, 27 KB as the pack expects) came to 36 KB and the kernel was refused. Split into
 * x, y and z arrays it takes the 27 KB again. Every use is rewritten: an element read becomes a get call, an element
 * assignment (=, +=, -=, *=, /=) a set call, and a read of a whole row (Photon passes shared_memory[0] to a function) a
 * vec3 array built from the elements. Applied only when the shared memory wouldn't fit otherwise; anything it can't
 * rewrite leaves the source as it was.
 */
final class SharedVec3Split {
  private static final Pattern SHARED = Pattern.compile("\\bshared\\s+(\\w+)\\s+(\\w+)\\s*((?:\\[\\s*\\d+\\s*\\]\\s*)*);");
  private static final Pattern DIM = Pattern.compile("\\[\\s*(\\d+)\\s*\\]");
  private static final int METAL_LIMIT = 32768;

  private SharedVec3Split() {
  }

  static String apply(String body) {
    List<String[]> vec3s = new ArrayList<>();  // {name, dims}
    long metalBytes = 0;
    Matcher m = SHARED.matcher(body);
    while (m.find()) {
      long count = 1;
      Matcher d = DIM.matcher(m.group(3));
      while (d.find()) count *= Long.parseLong(d.group(1));
      metalBytes += count * metalSize(m.group(1));
      if (m.group(1).equals("vec3") && !m.group(3).isBlank()) vec3s.add(new String[] {m.group(2), m.group(3)});
    }
    if (metalBytes <= METAL_LIMIT || vec3s.isEmpty()) return body;
    String out = body;
    for (String[] v : vec3s) {
      String rewritten = split(out, v[0], dims(v[1]));
      if (rewritten == null) return body;
      out = rewritten;
    }
    return out;
  }

  private static int metalSize(String type) {
    return switch (type) {
      case "float", "int", "uint", "bool" -> 4;
      case "vec2", "ivec2", "uvec2" -> 8;
      case "vec3", "ivec3", "uvec3", "vec4", "ivec4", "uvec4" -> 16;
      case "mat4" -> 64;
      case "mat3" -> 48;
      default -> 16;
    };
  }

  private static List<Integer> dims(String text) {
    List<Integer> out = new ArrayList<>();
    Matcher d = DIM.matcher(text);
    while (d.find()) out.add(Integer.parseInt(d.group(1)));
    return out;
  }

  /** The source with NAME's declaration and uses rewritten, or null when a use isn't one of the supported shapes. */
  private static String split(String source, String name, List<Integer> dims) {
    StringBuilder decl = new StringBuilder();
    String dimText = dims.stream().map(n -> "[" + n + "]").reduce("", String::concat);
    for (String c : new String[] {"x", "y", "z"}) decl.append("shared float ").append(name).append("_sl").append(c).append(dimText).append(";\n");
    StringBuilder params = new StringBuilder(), index = new StringBuilder();
    for (int i = 0; i < dims.size(); i++) {
      params.append(i == 0 ? "" : ", ").append("int i").append(i);
      index.append("[i").append(i).append(']');
    }
    decl.append("vec3 ").append(name).append("_slget(").append(params).append(") { return vec3(")
        .append(name).append("_slx").append(index).append(", ").append(name).append("_sly").append(index).append(", ")
        .append(name).append("_slz").append(index).append("); }\n");
    decl.append("void ").append(name).append("_slset(").append(params).append(", vec3 v) { ")
        .append(name).append("_slx").append(index).append(" = v.x; ").append(name).append("_sly").append(index).append(" = v.y; ")
        .append(name).append("_slz").append(index).append(" = v.z; }\n");
    Matcher declaration = Pattern.compile("\\bshared\\s+vec3\\s+" + Pattern.quote(name) + "\\s*(?:\\[\\s*\\d+\\s*\\]\\s*)+;").matcher(source);
    if (!declaration.find()) return null;
    String uses = uses(source.substring(declaration.end()), name, dims);
    return uses == null ? null : source.substring(0, declaration.start()) + decl + uses;
  }

  /** Every use of NAME in s rewritten (an assigned value's own uses included), or null. */
  private static String uses(String s, String name, List<Integer> dims) {
    StringBuilder out = new StringBuilder();
    Matcher use = Pattern.compile("\\b" + Pattern.quote(name) + "\\b(?!_sl)").matcher(s);
    int at = 0;
    while (use.find(at)) {
      int i = use.end();
      List<String> indices = new ArrayList<>();
      while (indices.size() < dims.size()) {
        int j = skipSpace(s, i);
        if (j >= s.length() || s.charAt(j) != '[') break;
        int close = matching(s, j, '[', ']');
        if (close < 0) return null;
        indices.add(s.substring(j + 1, close));
        i = close + 1;
      }
      out.append(s, at, use.start());
      if (indices.isEmpty()) return null;  // the bare name (passed whole, sized): not a shape handled here
      int next = skipSpace(s, i);
      String op = assignment(s, next);
      String args = indices.stream().map(x -> "int(" + x + ")").reduce((a, b) -> a + ", " + b).orElse("");
      if (indices.size() == dims.size() && op != null) {
        int valueStart = next + op.length();
        int end = statementEnd(s, valueStart);
        if (end < 0) return null;
        String value = uses(s.substring(valueStart, end).strip(), name, dims);
        if (value == null) return null;
        String combined = op.equals("=") ? "(" + value + ")"
            : name + "_slget(" + args + ") " + op.charAt(0) + " (" + value + ")";
        out.append(name).append("_slset(").append(args).append(", ").append(combined).append(')');
        at = end;  // (the statement's ';' follows)
        continue;
      }
      if (op != null) return null;  // a whole row assigned: not handled
      if (indices.size() == dims.size()) out.append(name).append("_slget(").append(args).append(')');
      else if (indices.size() == dims.size() - 1) {
        // a row read whole: vec3[n](get(..., 0), get(..., 1), ...)
        int n = dims.get(dims.size() - 1);
        out.append("vec3[").append(n).append("](");
        for (int k = 0; k < n; k++) out.append(k == 0 ? "" : ", ").append(name).append("_slget(").append(args).append(", ").append(k).append(')');
        out.append(')');
      } else return null;
      at = i;
    }
    out.append(s.substring(at));
    return out.toString();
  }

  private static int skipSpace(String s, int i) {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    return i;
  }

  private static int matching(String s, int open, char a, char b) {
    int depth = 0;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == a) depth++;
      else if (c == b && --depth == 0) return i;
    }
    return -1;
  }

  /** "=", "+=", "-=", "*=", "/=" at i, or null (comparisons excluded). */
  private static String assignment(String s, int i) {
    if (i >= s.length()) return null;
    char c = s.charAt(i);
    if (c == '=' && (i + 1 >= s.length() || s.charAt(i + 1) != '=')) return "=";
    if ("+-*/".indexOf(c) >= 0 && i + 1 < s.length() && s.charAt(i + 1) == '=') return c + "=";
    return null;
  }

  /** The index of the ';' ending the statement whose value starts at i (brackets and parentheses balanced). */
  private static int statementEnd(String s, int i) {
    int depth = 0;
    for (int k = i; k < s.length(); k++) {
      char c = s.charAt(k);
      if (c == '(' || c == '[' || c == '{') depth++;
      else if (c == ')' || c == ']' || c == '}') depth--;
      else if (c == ';' && depth == 0) return k;
      if (depth < 0) return -1;
    }
    return -1;
  }
}
