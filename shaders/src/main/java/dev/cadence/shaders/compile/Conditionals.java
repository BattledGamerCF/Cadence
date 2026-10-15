package dev.cadence.shaders.compile;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Cadence: a shader's conditional compilation (#if, #ifdef, #ifndef, #elif, #else, #endif) evaluated as Iris's
 * preprocessor evaluates it, before the GLSL compiler sees the source: decimal numbers allowed in conditions
 * ({@code #if SKY_LIGHT_FALLOFF == 1} with {@code #define SKY_LIGHT_FALLOFF 1.0}: the GLSL compiler stops at those and
 * drops the rest of the shader), conditionals left open at the end closed there. Lines of inactive branches and the
 * conditional directives themselves become empty lines (line numbers stay); #define and #undef in active branches stay
 * for the GLSL compiler, which still expands macros in code. Returns null (the source goes to the compiler as it was)
 * when a condition uses something this doesn't evaluate (function-like macros).
 */
final class Conditionals {
  private Conditionals() {
  }

  private static final class Unsupported extends RuntimeException {
    Unsupported(String what) {
      super(what, null, false, false);
    }
  }

  private record Branch(boolean parentActive, boolean active, boolean taken) {}

  static String apply(String source) {
    try {
      return run(source);
    } catch (Unsupported e) {
      return null;
    }
  }

  private static String run(String source) {
    Map<String, String> macros = new HashMap<>();
    java.util.Set<String> functionMacros = new java.util.HashSet<>();
    ArrayDeque<Branch> stack = new ArrayDeque<>();
    String[] lines = source.split("\n", -1);
    StringBuilder out = new StringBuilder(source.length());
    boolean inComment = false;
    boolean changed = false;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      boolean active = stack.isEmpty() || stack.peek().active();
      // a directive starts a line outside a block comment
      String code = inComment ? null : line;
      boolean wasInComment = inComment;
      inComment = commentStateAfter(line, inComment);
      if (code != null) {
        String t = code.strip();
        if (t.startsWith("#")) {
          // join continuation lines into the directive
          StringBuilder directive = new StringBuilder(t.substring(1).strip());
          int extra = 0;
          while (directive.length() > 0 && directive.charAt(directive.length() - 1) == '\\' && i + 1 + extra < lines.length) {
            directive.setLength(directive.length() - 1);
            extra++;
            directive.append(' ').append(lines[i + extra].strip());
          }
          String d = stripComments(directive.toString()).strip();
          String keyword = d.split("[\\s(]", 2)[0];
          String rest = d.substring(keyword.length()).strip();
          switch (keyword) {
            case "if", "ifdef", "ifndef" -> {
              boolean value = false;
              if (active) {
                value = switch (keyword) {
                  case "ifdef" -> macros.containsKey(name(rest)) || functionMacros.contains(name(rest));
                  case "ifndef" -> !macros.containsKey(name(rest)) && !functionMacros.contains(name(rest));
                  default -> new Expression(rest, macros, functionMacros).evaluate() != 0;
                };
              }
              stack.push(new Branch(active, active && value, active && value));
              changed = true;
              for (int k = 0; k <= extra; k++) out.append('\n');
              i += extra;
              continue;
            }
            case "elif" -> {
              Branch b = stack.isEmpty() ? null : stack.pop();
              if (b == null) throw new Unsupported("#elif without #if");
              boolean value = b.parentActive() && !b.taken() && new Expression(rest, macros, functionMacros).evaluate() != 0;
              stack.push(new Branch(b.parentActive(), value, b.taken() || value));
              for (int k = 0; k <= extra; k++) out.append('\n');
              i += extra;
              continue;
            }
            case "else" -> {
              Branch b = stack.isEmpty() ? null : stack.pop();
              if (b == null) throw new Unsupported("#else without #if");
              boolean value = b.parentActive() && !b.taken();
              stack.push(new Branch(b.parentActive(), value, true));
              out.append('\n');
              continue;
            }
            case "endif" -> {
              if (stack.isEmpty()) throw new Unsupported("#endif without #if");
              stack.pop();
              out.append('\n');
              continue;
            }
            case "define" -> {
              if (active) {
                int open = rest.indexOf('(');
                int space = indexOfSpace(rest);
                String name = name(rest);
                if (open >= 0 && (space < 0 || open < space) && open == name.length()) {
                  functionMacros.add(name);
                  macros.remove(name);
                } else {
                  macros.put(name, space < 0 ? "" : rest.substring(space).strip());
                  functionMacros.remove(name);
                }
              }
            }
            case "undef" -> {
              if (active) {
                macros.remove(name(rest));
                functionMacros.remove(name(rest));
              }
            }
            default -> {}
          }
          if (!active) {
            for (int k = 0; k <= extra; k++) out.append('\n');
            i += extra;
            continue;
          }
          for (int k = 0; k <= extra; k++) out.append(lines[i + k]).append('\n');
          i += extra;
          continue;
        }
      }
      out.append(active || wasInComment && active ? line : "").append('\n');
    }
    if (!changed) return source;
    out.setLength(out.length() - 1); // the split's last element had no newline after it
    return out.toString();
  }

  private static int indexOfSpace(String s) {
    for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
    return -1;
  }

  private static String name(String rest) {
    int end = 0;
    while (end < rest.length() && (Character.isLetterOrDigit(rest.charAt(end)) || rest.charAt(end) == '_')) end++;
    return rest.substring(0, end);
  }

  /** Whether a block comment is open after this line (strings in shaders don't hold comment markers). */
  private static boolean commentStateAfter(String line, boolean inComment) {
    for (int i = 0; i < line.length(); i++) {
      if (inComment) {
        if (line.startsWith("*/", i)) {
          inComment = false;
          i++;
        }
      } else if (line.startsWith("//", i)) {
        return false;
      } else if (line.startsWith("/*", i)) {
        inComment = true;
        i++;
      }
    }
    return inComment;
  }

  private static String stripComments(String s) {
    return s.replaceAll("/\\*.*?\\*/", " ").replaceAll("//.*$", "");
  }

  /** A C preprocessor expression over numbers (decimals too), defined(), and object-like macros. */
  private static final class Expression {
    private final Map<String, String> macros;
    private final java.util.Set<String> functionMacros;
    private String s;
    private int p;
    private int depth;

    Expression(String text, Map<String, String> macros, java.util.Set<String> functionMacros) {
      this.macros = macros;
      this.functionMacros = functionMacros;
      this.s = text;
    }

    double evaluate() {
      p = 0;
      double v = conditional();
      skip();
      if (p != s.length()) throw new Unsupported("trailing tokens in #if " + s);
      return v;
    }

    private void skip() {
      while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
    }

    private boolean take(String op) {
      skip();
      if (s.startsWith(op, p)) {
        // don't take "<" of "<<" or "<=", "&" of "&&", "|" of "||", "=" alone, "!" of "!="
        if (op.length() == 1 && p + 1 < s.length()) {
          char next = s.charAt(p + 1);
          if ((op.equals("<") || op.equals(">")) && (next == '=' || next == op.charAt(0))) return false;
          if ((op.equals("&") || op.equals("|")) && next == op.charAt(0)) return false;
          if (op.equals("!") && next == '=') return false;
        }
        p += op.length();
        return true;
      }
      return false;
    }

    private double conditional() {
      double c = binary(0);
      if (take("?")) {
        double a = conditional();
        if (!take(":")) throw new Unsupported("?: in #if");
        double b = conditional();
        return c != 0 ? a : b;
      }
      return c;
    }

    private static final String[][] LEVELS = {
      {"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<=", ">=", "<", ">"}, {"<<", ">>"}, {"+", "-"}, {"*", "/", "%"}
    };

    private double binary(int level) {
      if (level == LEVELS.length) return unary();
      double left = binary(level + 1);
      while (true) {
        String op = null;
        for (String candidate : LEVELS[level]) if (take(candidate)) {
          op = candidate;
          break;
        }
        if (op == null) return left;
        if (op.equals("||")) {
          double right = binary(level + 1);
          left = left != 0 || right != 0 ? 1 : 0;
          continue;
        }
        if (op.equals("&&")) {
          double right = binary(level + 1);
          left = left != 0 && right != 0 ? 1 : 0;
          continue;
        }
        double right = binary(level + 1);
        left = switch (op) {
          case "|" -> (long) left | (long) right;
          case "^" -> (long) left ^ (long) right;
          case "&" -> (long) left & (long) right;
          case "==" -> left == right ? 1 : 0;
          case "!=" -> left != right ? 1 : 0;
          case "<=" -> left <= right ? 1 : 0;
          case ">=" -> left >= right ? 1 : 0;
          case "<" -> left < right ? 1 : 0;
          case ">" -> left > right ? 1 : 0;
          case "<<" -> (long) left << (long) right;
          case ">>" -> (long) left >> (long) right;
          case "+" -> left + right;
          case "-" -> left - right;
          case "*" -> left * right;
          case "/" -> right == 0 ? 0 : (isInteger(left) && isInteger(right) ? (double) ((long) left / (long) right) : left / right);
          case "%" -> right == 0 ? 0 : (long) left % (long) right;
          default -> throw new Unsupported(op);
        };
      }
    }

    private static boolean isInteger(double v) {
      return v == Math.rint(v);
    }

    private double unary() {
      if (take("!")) return unary() == 0 ? 1 : 0;
      if (take("-")) return -unary();
      if (take("+")) return unary();
      if (take("~")) return ~(long) unary();
      if (take("(")) {
        double v = conditional();
        if (!take(")")) throw new Unsupported("unbalanced ( in #if");
        return v;
      }
      skip();
      if (p >= s.length()) throw new Unsupported("empty #if");
      char c = s.charAt(p);
      if (Character.isDigit(c) || c == '.') return number();
      if (Character.isLetter(c) || c == '_') {
        int start = p;
        while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '_')) p++;
        String id = s.substring(start, p);
        if (id.equals("defined")) {
          boolean paren = take("(");
          skip();
          int a = p;
          while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '_')) p++;
          String n = s.substring(a, p);
          if (paren && !take(")")) throw new Unsupported("defined( in #if");
          return macros.containsKey(n) || functionMacros.contains(n) ? 1 : 0;
        }
        if (functionMacros.contains(id)) throw new Unsupported("function-like macro " + id + " in #if");
        String value = macros.get(id);
        if (value == null) return 0; // an undefined name is 0
        if (++depth > 64) throw new Unsupported("macro recursion in #if");
        try {
          String text = stripComments(value).strip();
          if (text.isEmpty()) return 0;
          return new Expression(text, macros, functionMacros).nested(depth).evaluate();
        } finally {
          depth--;
        }
      }
      throw new Unsupported("token '" + c + "' in #if");
    }

    private Expression nested(int d) {
      this.depth = d;
      return this;
    }

    private double number() {
      int start = p;
      if (s.startsWith("0x", p) || s.startsWith("0X", p)) {
        p += 2;
        while (p < s.length() && Character.digit(s.charAt(p), 16) >= 0) p++;
        long v = Long.parseLong(s.substring(start + 2, p), 16);
        while (p < s.length() && "uUlL".indexOf(s.charAt(p)) >= 0) p++;
        return v;
      }
      while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) p++;
      if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
        p++;
        if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) p++;
        while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
      }
      String text = s.substring(start, p);
      while (p < s.length() && "uUlLfF".indexOf(s.charAt(p)) >= 0) p++;
      return Double.parseDouble(text);
    }
  }
}
