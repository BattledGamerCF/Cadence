// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.pack;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conditional properties and program gates. GLSL itself is preprocessed by the shader compiler;
 * this parser only evaluates the scalar expressions used by pack configuration files.
 */
public final class ShaderProperties {
  private static final Pattern DIRECTIVE =
      Pattern.compile("^\\s*#\\s*(if|ifdef|ifndef|elif|else|endif|define|undef|error)\\b(.*)$");
  private final Map<String, String> values;

  private ShaderProperties(Map<String, String> values) {
    this.values = Collections.unmodifiableMap(new TreeMap<>(values));
  }

  public Map<String, String> values() {
    return values;
  }

  public String get(String name, String fallback) {
    return values.getOrDefault(name, fallback);
  }

  public boolean programEnabled(String identifier, Map<String, String> definitions)
      throws ShaderPackException {
    String condition = values.get("program." + identifier + ".enabled");
    return condition == null || evaluate(condition, definitions);
  }

  public static ShaderProperties parse(String source, Map<String, String> definitions)
      throws ShaderPackException {
    Properties parsed = new Properties();
    try {
      dev.cadence.metal.system.ConfigFiles.load(parsed, new StringReader(filter(source, definitions)));
    } catch (IOException | IllegalArgumentException e) {
      throw new ShaderPackException("Invalid shader properties: " + e.getMessage(), e);
    }
    Map<String, String> result = new TreeMap<>();
    for (String key : parsed.stringPropertyNames()) {
      result.put(key, parsed.getProperty(key).trim());
    }
    return new ShaderProperties(result);
  }

  private static final class Branch {
    final boolean parentActive;
    boolean selected;
    boolean active;
    boolean sawElse;

    Branch(boolean parentActive, boolean condition) {
      this.parentActive = parentActive;
      selected = condition;
      active = parentActive && condition;
    }
  }

  /** Keeps only active property lines; values are not macro-expanded. */
  private static String filter(String source, Map<String, String> definitions)
      throws ShaderPackException {
    Map<String, String> macros = new HashMap<>(definitions);
    ArrayDeque<Branch> branches = new ArrayDeque<>();
    StringBuilder result = new StringBuilder();
    int lineNumber = 0;
    for (String line : source.split("\\R", -1)) {
      lineNumber++;
      boolean active = branches.isEmpty() || branches.peek().active;
      Matcher directive = DIRECTIVE.matcher(line);
      if (!directive.matches()) {
        result.append(active ? line : "").append('\n');
        continue;
      }
      String operation = directive.group(1);
      String argument = directive.group(2).replaceFirst("//.*$", "").trim();
      try {
        switch (operation) {
          case "if", "ifdef", "ifndef" -> {
            boolean condition = false;
            if (active) {
              condition =
                  switch (operation) {
                    case "ifdef" -> macros.containsKey(identifier(argument));
                    case "ifndef" -> !macros.containsKey(identifier(argument));
                    default -> evaluate(argument, macros);
                  };
            }
            branches.push(new Branch(active, condition));
          }
          case "elif" -> {
            Branch branch = requireBranch(branches, operation);
            if (branch.sawElse) throw new ShaderPackException("#elif after #else");
            branch.active = branch.parentActive && !branch.selected && evaluate(argument, macros);
            branch.selected |= branch.active;
          }
          case "else" -> {
            Branch branch = requireBranch(branches, operation);
            if (branch.sawElse) throw new ShaderPackException("Duplicate #else");
            if (!argument.isEmpty()) throw new ShaderPackException("Unexpected text after #else");
            branch.sawElse = true;
            branch.active = branch.parentActive && !branch.selected;
            branch.selected = true;
          }
          case "endif" -> {
            requireBranch(branches, operation);
            if (!argument.isEmpty()) throw new ShaderPackException("Unexpected text after #endif");
            branches.pop();
          }
          case "define" -> {
            if (active) {
              String[] parts = argument.split("\\s+", 2);
              macros.put(identifier(parts[0]), parts.length == 1 ? "" : parts[1]);
            }
          }
          case "undef" -> {
            if (active) macros.remove(identifier(argument));
          }
          case "error" -> {
            if (active) throw new ShaderPackException("#error " + argument);
          }
          default -> throw new AssertionError(operation);
        }
      } catch (ShaderPackException e) {
        throw new ShaderPackException("Properties line " + lineNumber + ": " + e.getMessage(), e);
      }
      result.append('\n');
    }
    if (!branches.isEmpty())
      throw new ShaderPackException("Unterminated conditional in properties");
    return result.toString();
  }

  private static Branch requireBranch(ArrayDeque<Branch> branches, String operation)
      throws ShaderPackException {
    if (branches.isEmpty()) throw new ShaderPackException("Unmatched #" + operation);
    return branches.peek();
  }

  private static String identifier(String text) throws ShaderPackException {
    if (!text.matches("[A-Za-z_][A-Za-z_0-9]*")) {
      throw new ShaderPackException("Expected identifier, found '" + text + "'");
    }
    return text;
  }

  public static boolean evaluate(String expression, Map<String, String> definitions)
      throws ShaderPackException {
    return new Expression(expression, definitions, new ArrayDeque<>()).parse() != 0;
  }

  /** Small precedence parser; unsupported syntax is an error, never an implicit enabled program. */
  private static final class Expression {
    private static final String[][] OPERATORS = {
      {"||"},
      {"&&"},
      {"|"},
      {"^"},
      {"&"},
      {"==", "!="},
      {"<=", ">=", "<", ">"},
      {"<<", ">>"},
      {"+", "-"},
      {"*", "/", "%"}
    };
    private final String source;
    private final Map<String, String> definitions;
    private final ArrayDeque<String> expanding;
    private int position;

    Expression(String source, Map<String, String> definitions, ArrayDeque<String> expanding) {
      this.source = source;
      this.definitions = definitions;
      this.expanding = expanding;
    }

    double parse() throws ShaderPackException {
      double value = binary(0, true);
      whitespace();
      if (position != source.length()) throw error("Unsupported expression syntax");
      return value;
    }

    double binary(int level, boolean execute) throws ShaderPackException {
      if (level == OPERATORS.length) return unary(execute);
      double left = binary(level + 1, execute);
      while (true) {
        String matched = null;
        for (String operator : OPERATORS[level]) {
          if (take(operator)) {
            matched = operator;
            break;
          }
        }
        if (matched == null) return left;
        boolean rightExecute =
            execute && !(matched.equals("&&") && left == 0) && !(matched.equals("||") && left != 0);
        double right = binary(level + 1, rightExecute);
        if (execute) left = calculate(matched, left, right);
      }
    }

    double unary(boolean execute) throws ShaderPackException {
      if (take("!")) return unary(execute) == 0 ? 1 : 0;
      if (take("~")) return ~(long) unary(execute);
      if (take("-")) return -unary(execute);
      if (take("+")) return unary(execute);
      if (take("(")) {
        double value = binary(0, execute);
        if (!take(")")) throw error("Missing closing parenthesis");
        return value;
      }
      whitespace();
      int start = position;
      if (position < source.length()
          && (Character.isLetter(source.charAt(position)) || source.charAt(position) == '_')) {
        position++;
        while (position < source.length()
            && (Character.isLetterOrDigit(source.charAt(position))
                || source.charAt(position) == '_')) {
          position++;
        }
        String name = source.substring(start, position);
        if (name.equals("defined")) {
          boolean parenthesis = take("(");
          whitespace();
          int nameStart = position;
          while (position < source.length()
              && (Character.isLetterOrDigit(source.charAt(position))
                  || source.charAt(position) == '_')) {
            position++;
          }
          String macro = identifier(source.substring(nameStart, position));
          if (parenthesis && !take(")")) throw error("Missing parenthesis after defined");
          return definitions.containsKey(macro) ? 1 : 0;
        }
        if (!execute) return 0;
        if (name.equals("true")) return 1;
        if (name.equals("false")) return 0;
        String replacement = definitions.get(name);
        if (replacement == null) return 0;
        if (replacement.isBlank()) return 1;
        if (expanding.contains(name) || expanding.size() >= 64)
          throw error("Recursive macro " + name);
        expanding.push(name);
        try {
          return new Expression(replacement, definitions, expanding).parse();
        } finally {
          expanding.pop();
        }
      }
      while (position < source.length()
          && (Character.isLetterOrDigit(source.charAt(position))
              || source.charAt(position) == '.')) {
        position++;
      }
      if (start == position) throw error("Expected scalar value");
      String token = source.substring(start, position);
      try {
        if (token.startsWith("0x") || token.startsWith("0X")) {
          return Long.parseUnsignedLong(token.substring(2).replaceFirst("[uUlL]+$", ""), 16);
        }
        return Double.parseDouble(token.replaceFirst("[uUlLfF]+$", ""));
      } catch (NumberFormatException e) {
        throw error("Invalid scalar value " + token);
      }
    }

    private double calculate(String operator, double a, double b) throws ShaderPackException {
      return switch (operator) {
        case "||" -> a != 0 || b != 0 ? 1 : 0;
        case "&&" -> a != 0 && b != 0 ? 1 : 0;
        case "|" -> (long) a | (long) b;
        case "^" -> (long) a ^ (long) b;
        case "&" -> (long) a & (long) b;
        case "==" -> a == b ? 1 : 0;
        case "!=" -> a != b ? 1 : 0;
        case "<=" -> a <= b ? 1 : 0;
        case ">=" -> a >= b ? 1 : 0;
        case "<" -> a < b ? 1 : 0;
        case ">" -> a > b ? 1 : 0;
        case "<<" -> (long) a << (long) b;
        case ">>" -> (long) a >> (long) b;
        case "+" -> a + b;
        case "-" -> a - b;
        case "*" -> a * b;
        case "/", "%" -> {
          if (b == 0) throw error("Division by zero");
          yield operator.equals("/") ? a / b : a % b;
        }
        default -> throw new AssertionError(operator);
      };
    }

    boolean take(String token) {
      whitespace();
      if (!source.startsWith(token, position)) return false;
      if (token.length() == 1 && position + 1 < source.length()) {
        char next = source.charAt(position + 1);
        if ((token.equals("|") && next == '|')
            || (token.equals("&") && next == '&')
            || (token.equals("<") && (next == '<' || next == '='))
            || (token.equals(">") && (next == '>' || next == '='))
            || (token.equals("!") && next == '=')) return false;
      }
      position += token.length();
      return true;
    }

    void whitespace() {
      while (position < source.length() && Character.isWhitespace(source.charAt(position)))
        position++;
    }

    ShaderPackException error(String message) {
      return new ShaderPackException(
          message + " at column " + (position + 1) + " in '" + source + "'");
    }
  }
}
