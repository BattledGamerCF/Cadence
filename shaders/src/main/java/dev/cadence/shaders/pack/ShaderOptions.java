// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.pack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Discovers declarations without treating examples inside block comments as real options. */
final class ShaderOptions {
  private static final Pattern DEFINE =
      Pattern.compile("^[\\t ]*(//[\\t ]*)?#[\\t ]*define[\\t ]+([A-Za-z_][A-Za-z_0-9]*)(.*)$");
  private static final Pattern CONSTANT =
      Pattern.compile(
          "^[\\t ]*const[\\t ]+(bool|int|float)[\\t ]+([A-Za-z_][A-Za-z_0-9]*)[\\t ]*=[\\t"
              + " ]*([^;]+);(.*)$");
  private static final Pattern RANGE = Pattern.compile("//\\s*\\[([^]]+)\\]");
  private static final Pattern SWITCH_USE =
      Pattern.compile("(?m)^[\\t ]*#[\\t ]*ifn?def[\\t ]+([A-Za-z_][A-Za-z_0-9]*)");
  private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");

  private record Declaration(int line, ShaderOption option) {}

  private final Map<String, ShaderOption> options;
  private final Map<String, List<Declaration>> declarations;

  private ShaderOptions(
      Map<String, ShaderOption> options, Map<String, List<Declaration>> declarations) {
    this.options = Collections.unmodifiableMap(new TreeMap<>(options));
    this.declarations = Map.copyOf(declarations);
  }

  static ShaderOptions discover(Map<String, byte[]> files) throws ShaderPackException {
    Map<String, String> sources = new TreeMap<>();
    Set<String> usedSwitches = new HashSet<>();
    Set<String> explicitOptions = new HashSet<>();
    boolean explicitScreen = false;
    if (files.containsKey("shaders.properties")) {
      String properties = new String(files.get("shaders.properties"), StandardCharsets.UTF_8);
      for (String line : properties.split("\\R")) {
        if (line.matches("\\s*screen[.=].*")) explicitScreen = true;
        if (line.matches("\\s*(screen[.=]|profile\\.|program\\.).*")) {
          Matcher words = WORD.matcher(line);
          while (words.find()) explicitOptions.add(words.group());
        }
      }
    }
    // Cadence: any file a shader #includes holds options too, whatever its name (Sildur's keep theirs in shaders.settings)
    Set<String> included = new HashSet<>();
    java.util.regex.Pattern include = java.util.regex.Pattern.compile("(?m)^[ \\t]*#[ \\t]*include[ \\t]+\"([^\"]+)\"");
    for (var entry : files.entrySet()) {
      if (!entry.getKey().matches(".*\\.(glsl|vsh|fsh|csh|gsh|tcs|tes|inc)$")) continue;
      Matcher m = include.matcher(new String(entry.getValue(), StandardCharsets.UTF_8));
      while (m.find()) included.add(m.group(1).substring(m.group(1).lastIndexOf('/') + 1));
    }
    for (var entry : files.entrySet()) {
      String base = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
      if (!entry.getKey().matches(".*\\.(glsl|vsh|fsh|csh|gsh|tcs|tes|inc)$") && !included.contains(base)) continue;
      String source =
          withoutBlockComments(
              new String(entry.getValue(), StandardCharsets.UTF_8)
                  .replace("\r\n", "\n")
                  .replace('\r', '\n'));
      sources.put(entry.getKey(), source);
      Matcher uses = SWITCH_USE.matcher(source);
      while (uses.find()) usedSwitches.add(uses.group(1));
    }
    Map<String, ShaderOption> options = new TreeMap<>();
    Map<String, List<Declaration>> declarations = new HashMap<>();
    // Cadence: an option declared differently in two files (Pegasus: sunPathRotation 30.0 in one, 5.0 in another) is
    // ambiguous; Iris then doesn't offer it and each file keeps its own value. Refusing the whole pack was stricter than that.
    java.util.Set<String> ambiguous = new java.util.HashSet<>();
    for (var entry : sources.entrySet()) {
      String[] lines = entry.getValue().split("\n", -1);
      List<Declaration> fileDeclarations = new ArrayList<>();
      for (int line = 0; line < lines.length; line++) {
        Matcher define = DEFINE.matcher(lines[line]);
        Matcher constant = CONSTANT.matcher(lines[line]);
        ShaderOption option = null;
        if (define.matches()) {
          String name = define.group(2);
          String rest = define.group(3);
          int comment = rest.indexOf("//");
          String value = (comment < 0 ? rest : rest.substring(0, comment)).trim();
          Matcher range = RANGE.matcher(rest);
          if (value.isEmpty()
              && usedSwitches.contains(name)
              && (!explicitScreen || explicitOptions.contains(name) || define.group(1) != null)) {
            option =
                new ShaderOption(
                    name,
                    ShaderOption.Kind.SWITCH,
                    define.group(1) == null ? "true" : "false",
                    List.of("true", "false"));
          } else if (define.group(1) == null && range.find()) {
            option =
                new ShaderOption(name, ShaderOption.Kind.DEFINE, value, values(range.group(1)));
          }
        } else if (constant.matches()) {
          Matcher range = RANGE.matcher(constant.group(4));
          if (range.find()) {
            option =
                new ShaderOption(
                    constant.group(2),
                    ShaderOption.Kind.CONSTANT,
                    constant.group(3).trim(),
                    values(range.group(1)));
          }
        }
        if (option != null) {
          ShaderOption previous = options.putIfAbsent(option.name(), option);
          if (previous != null && !previous.equals(option)) ambiguous.add(option.name());
          fileDeclarations.add(new Declaration(line, option));
        }
      }
      declarations.put(entry.getKey(), List.copyOf(fileDeclarations));
    }
    if (!ambiguous.isEmpty()) {
      options.keySet().removeAll(ambiguous);
      declarations.replaceAll(
          (file, list) -> list.stream().filter(d -> !ambiguous.contains(d.option().name())).toList());
    }
    return new ShaderOptions(options, declarations);
  }

  private static List<String> values(String contents) {
    return List.of(contents.trim().split("\\s+"));
  }

  Map<String, ShaderOption> options() {
    return options;
  }

  Map<String, String> values(Map<String, String> overrides) throws ShaderPackException {
    Map<String, String> values = new TreeMap<>();
    options.forEach((name, option) -> values.put(name, option.defaultValue()));
    for (var entry : overrides.entrySet()) {
      // Cadence: as OptiFine and Iris, a value the pack doesn't list (a profile's SUNRAYS_QUALITY=16 in I Like Vanilla) is
      // used, an option the pack no longer has (an older pack's saved settings, a profile naming a removed option) is
      // ignored, and only a switch set to something other than true/false keeps its default. These used to refuse the pack.
      ShaderOption option = options.get(entry.getKey());
      if (option == null) {
        org.slf4j.LoggerFactory.getLogger("cadence_shaders").warn("Ignoring unknown shader option {}", entry.getKey());
        continue;
      }
      String value = entry.getValue().strip();
      if (option.kind() == ShaderOption.Kind.SWITCH && !value.equals("true") && !value.equals("false")) {
        org.slf4j.LoggerFactory.getLogger("cadence_shaders").warn("Ignoring value '{}' for switch {}", value, entry.getKey());
        continue;
      }
      if (!option.allowedValues().contains(value) && !value.matches("[-+]?[0-9.]+[fF]?|[A-Za-z_][A-Za-z0-9_]*")) {
        org.slf4j.LoggerFactory.getLogger("cadence_shaders").warn("Ignoring value '{}' for {}", value, entry.getKey());
        continue;
      }
      values.put(entry.getKey(), value);
    }
    return Collections.unmodifiableMap(values);
  }

  String replace(String path, String source, Map<String, String> overrides) {
    if (overrides.isEmpty()) return source;
    String[] lines = source.split("\n", -1);
    for (Declaration declaration : declarations.getOrDefault(path, List.of())) {
      ShaderOption option = declaration.option();
      String value = overrides.get(option.name());
      if (value == null) continue;
      String line = lines[declaration.line()];
      if (option.kind() == ShaderOption.Kind.SWITCH) {
        lines[declaration.line()] = (value.equals("true") ? "" : "//") + "#define " + option.name();
      } else if (option.kind() == ShaderOption.Kind.DEFINE) {
        int comment = line.indexOf("//");
        lines[declaration.line()] =
            "#define "
                + option.name()
                + " "
                + value
                + (comment < 0 ? "" : " " + line.substring(comment));
      } else {
        int equals = line.indexOf('=');
        int semicolon = line.indexOf(';', equals);
        lines[declaration.line()] =
            line.substring(0, equals + 1) + " " + value + line.substring(semicolon);
      }
    }
    return String.join("\n", lines);
  }

  /** Removes block comments while preserving line numbers and literal quoted paths. */
  static String withoutBlockComments(String source) {
    StringBuilder result = new StringBuilder(source);
    boolean block = false;
    boolean line = false;
    boolean quoted = false;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
      if (c == '\n') {
        line = false;
        continue;
      }
      if (block) {
        result.setCharAt(i, ' ');
        if (c == '*' && next == '/') {
          result.setCharAt(++i, ' ');
          block = false;
        }
      } else if (!line) {
        if (quoted && c == '\\') {
          i++;
        } else if (c == '"') {
          quoted = !quoted;
        } else if (!quoted && c == '/' && next == '/') {
          line = true;
          i++;
        } else if (!quoted && c == '/' && next == '*') {
          block = true;
          result.setCharAt(i, ' ');
          result.setCharAt(++i, ' ');
        }
      }
    }
    return result.toString();
  }
}
