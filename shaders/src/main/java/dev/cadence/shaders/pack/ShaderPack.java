// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * An immutable in-memory pack snapshot. Files are read and archives closed before GPU compilation;
 * rendering never touches the ZIP or observes files changing after pack activation.
 */
public final class ShaderPack {
  private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
  private static final long MAX_PACK_BYTES = 256L * 1024 * 1024;
  private static final int MAX_FILES = 16_384;
  private static final int MAX_INCLUDE_DEPTH = 64;
  private static final int MAX_EXPANDED_CHARS = 16 * 1024 * 1024;
  private static final Pattern INCLUDE =
      Pattern.compile("^\\s*#\\s*include\\s+\"([^\"]+)\"\\s*(?://.*)?$");
  private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#\\s*version[^\\n]*\\n?");
  private static final Pattern LEADING_IF = Pattern.compile("(?m)^[ \\t]*#[ \\t]*if");
  private final Path location;
  private final Map<String, byte[]> files;
  private final Set<String> directories;
  private final ShaderOptions options;
  private final String contentFingerprint;

  private ShaderPack(Path location, Map<String, byte[]> files, Set<String> directories)
      throws ShaderPackException {
    this.location = location;
    this.files = Collections.unmodifiableMap(new TreeMap<>(files));
    this.directories = Set.copyOf(directories);
    this.options = ShaderOptions.discover(this.files);
    this.contentFingerprint = fingerprint(this.files);
  }

  /** Canonical raw snapshot bytes, independent of archive name, ordering or compression. */
  private static String fingerprint(Map<String, byte[]> files) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update("MinecraftShaderLoader-PackSnapshot-v1\0".getBytes(StandardCharsets.UTF_8));
      fingerprintLength(digest, files.size(), 4);
      for (var entry : files.entrySet()) {
        byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
        fingerprintLength(digest, name.length, 4);
        digest.update(name);
        fingerprintLength(digest, entry.getValue().length, 8);
        digest.update(entry.getValue());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("Java requires SHA-256", impossible);
    }
  }

  private static void fingerprintLength(MessageDigest digest, long value, int bytes) {
    for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8)
      digest.update((byte) (value >>> shift));
  }

  public static ShaderPack load(Path location) throws IOException {
    Path absolute = location.toAbsolutePath().normalize();
    Map<String, byte[]> files = new TreeMap<>();
    Set<String> directories = new HashSet<>();
    if (Files.isDirectory(absolute)) {
      Path shaders = absolute.resolve("shaders");
      if (!Files.isDirectory(shaders) && absolute.getFileName().toString().equals("shaders")) {
        shaders = absolute;
      }
      if (!Files.isDirectory(shaders))
        throw new ShaderPackException("Pack has no shaders directory: " + absolute);
      if (Files.isSymbolicLink(shaders))
        throw new ShaderPackException(
            "Pack shaders directory cannot be a symbolic link: " + shaders);
      Path root = shaders.toRealPath();
      long total = 0;
      try (var entries = Files.walk(root)) {
        for (Path entry : entries.sorted().toList()) {
          if (Files.isSymbolicLink(entry)) {
            throw new ShaderPackException(
                "Symbolic links are not supported inside shader packs: " + entry);
          }
          String name = root.relativize(entry).toString().replace('\\', '/');
          if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            directories.add(name);
          } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            try (InputStream input = Files.newInputStream(entry)) {
              byte[] bytes = boundedRead(input, name);
              total = addFile(files, name, bytes, total);
            }
          }
        }
      }
    } else {
      try (ZipFile zip = new ZipFile(absolute.toFile())) {
        List<? extends java.util.zip.ZipEntry> entries = zip.stream().toList();
        if (entries.size() > MAX_FILES * 2)
          throw new ShaderPackException("Shader pack contains too many entries");
        Set<String> candidates = new TreeSet<>();
        for (var entry : entries) {
          String name = archivePath(entry.getName());
          int index = name.indexOf("shaders/");
          if (index >= 0 && (index == 0 || name.charAt(index - 1) == '/')) {
            if (!name.startsWith("__MACOSX/")) candidates.add(name.substring(0, index + 8));
          }
        }
        String prefix;
        if (candidates.contains("shaders/")) prefix = "shaders/";
        else if (candidates.size() == 1) prefix = candidates.iterator().next();
        else
          throw new ShaderPackException(
              "Expected one shaders directory in ZIP, found " + candidates);
        long total = 0;
        for (var entry : entries) {
          String archiveName = archivePath(entry.getName());
          if (!archiveName.startsWith(prefix)) continue;
          String name = archiveName.substring(prefix.length());
          if (entry.isDirectory()) {
            directories.add(name.replaceFirst("/$", ""));
          } else {
            try (InputStream input = zip.getInputStream(entry)) {
              total = addFile(files, name, boundedRead(input, name), total);
            }
          }
        }
      }
    }
    if (files.isEmpty()) throw new ShaderPackException("Shader pack contains no files");
    directories.add("");
    for (String file : files.keySet()) {
      for (int slash = file.lastIndexOf('/');
          slash >= 0;
          slash = file.lastIndexOf('/', slash - 1)) {
        directories.add(file.substring(0, slash));
      }
    }
    return new ShaderPack(absolute, files, directories);
  }

  private static long addFile(Map<String, byte[]> files, String name, byte[] bytes, long total)
      throws ShaderPackException {
    if (files.size() >= MAX_FILES)
      throw new ShaderPackException("Shader pack contains too many files");
    if (files.putIfAbsent(name, bytes) != null)
      throw new ShaderPackException("Duplicate pack path: " + name);
    total += bytes.length;
    if (total > MAX_PACK_BYTES)
      throw new ShaderPackException("Shader pack exceeds 256 MiB uncompressed limit");
    return total;
  }

  private static byte[] boundedRead(InputStream input, String name) throws IOException {
    byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
    if (bytes.length > MAX_FILE_BYTES)
      throw new ShaderPackException("Pack file exceeds 32 MiB: " + name);
    return bytes;
  }

  private static String archivePath(String name) throws ShaderPackException {
    if (name.startsWith("/")
        || name.indexOf('\\') >= 0
        || name.indexOf('\0') >= 0
        || name.indexOf(':') >= 0) {
      throw new ShaderPackException("Unsafe ZIP path: " + name);
    }
    for (String component : name.split("/")) {
      if (component.equals("..") || component.equals("."))
        throw new ShaderPackException("Unsafe ZIP path: " + name);
    }
    if (name.contains("//")) throw new ShaderPackException("Ambiguous ZIP path: " + name);
    return name;
  }

  public Path location() {
    return location;
  }

  public String name() {
    return location.getFileName().toString();
  }

  public Set<String> files() {
    return files.keySet();
  }

  /** Computed once from the immutable loaded snapshot; this never reads the pack from disk. */
  public String contentFingerprint() {
    return contentFingerprint;
  }

  public boolean contains(String path) throws ShaderPackException {
    return files.containsKey(normalize(path));
  }

  public byte[] bytes(String path) throws ShaderPackException {
    return content(path).clone();
  }

  public String text(String path) throws ShaderPackException {
    return new String(content(path), StandardCharsets.UTF_8)
        .replace("\r\n", "\n")
        .replace('\r', '\n');
  }

  private byte[] content(String path) throws ShaderPackException {
    String normalized = normalize(path);
    byte[] bytes = files.get(normalized);
    if (bytes == null) throw new ShaderPackException("Missing pack file: " + normalized);
    return bytes;
  }

  public Map<String, ShaderOption> options() {
    return options.options();
  }

  public Map<String, String> optionValues(Map<String, String> overrides)
      throws ShaderPackException {
    return options.values(overrides);
  }

  /**
   * Macro definitions for properties: disabled switches are absent, numeric zero remains defined.
   */
  public Map<String, String> definitions(
      Map<String, String> overrides, Map<String, String> environment) throws ShaderPackException {
    Map<String, String> result = new TreeMap<>(environment);
    for (var value : optionValues(overrides).entrySet()) {
      ShaderOption option = options.options().get(value.getKey());
      if (option.kind() == ShaderOption.Kind.SWITCH) {
        if (value.getValue().equals("true")) result.put(value.getKey(), "");
        else result.remove(value.getKey());
      } else result.put(value.getKey(), value.getValue());
    }
    return Collections.unmodifiableMap(result);
  }

  public ShaderProperties properties(Map<String, String> overrides, Map<String, String> environment)
      throws ShaderPackException {
    String source = files.containsKey("shaders.properties") ? text("shaders.properties") : "";
    return ShaderProperties.parse(source, definitions(overrides, environment));
  }

  /** Profiles change only the declared options, not every setting in a quality category. */
  public Map<String, String> profileOverrides(String profile) throws ShaderPackException {
    // (the loader's own macros too: Pegasus declares a profile only #if defined IS_IRIS)
    ShaderProperties properties = properties(Map.of(), dev.cadence.shaders.runtime.PackEnvironment.definitions());
    Map<String, String> result = new TreeMap<>();
    // Cadence: a profile this pack doesn't have (one saved for another pack, or removed in a newer version) is ignored, as
    // OptiFine and Iris do: the pack loads with its defaults instead of being refused
    if (!properties.values().containsKey("profile." + profile)) {
      org.slf4j.LoggerFactory.getLogger("cadence_shaders").warn("{} has no shader profile {}: using its defaults", name(), profile);
      return Map.of();
    }
    expandProfile(profile, properties.values(), result, new ArrayDeque<>());
    // (options a profile names that the pack doesn't have are left out: they'd be ignored anyway)
    result.keySet().retainAll(options.options().keySet());
    options.values(result);
    return Collections.unmodifiableMap(result);
  }

  private void expandProfile(
      String name,
      Map<String, String> properties,
      Map<String, String> result,
      ArrayDeque<String> stack)
      throws ShaderPackException {
    String profile = properties.get("profile." + name);
    if (profile == null) throw new ShaderPackException("Unknown shader profile: " + name);
    if (stack.contains(name) || stack.size() >= 64)
      throw new ShaderPackException("Recursive shader profile: " + name);
    stack.push(name);
    for (String token : profile.trim().split("\\s+")) {
      if (token.isEmpty()) continue;
      if (token.startsWith("profile.")) {
        // (a profile naming one that doesn't exist: that part is skipped)
        if (properties.containsKey(token)) expandProfile(token.substring(8), properties, result, stack);
        else org.slf4j.LoggerFactory.getLogger("cadence_shaders").warn("Shader profile {} names a missing {}", name, token);
      } else if (token.startsWith("!")) {
        result.put(token.substring(1), "false");
      } else if (token.contains("=") || token.contains(":")) {
        // OptiFine's profile syntax is OPTION:value (Pegasus, most packs); OPTION=value is accepted too, as Iris does
        int colon = token.indexOf(':'), equals = token.indexOf('=');
        int at = colon < 0 ? equals : equals < 0 ? colon : Math.min(colon, equals);
        result.put(token.substring(0, at), token.substring(at + 1));
      } else result.put(token, "true");
    }
    stack.pop();
  }

  /**
   * Selects an entire program directory. Missing stages never fall back to a different dimension.
   * Explicit dimension.properties mappings take precedence over legacy worldN directories.
   */
  public String dimensionFolder(String dimensionId) throws ShaderPackException {
    String dimension = dimensionId.contains(":") ? dimensionId : "minecraft:" + dimensionId;
    if (files.containsKey("dimension.properties")) {
      Map<String, String> mapping =
          ShaderProperties.parse(text("dimension.properties"), Map.of()).values();
      String selected = null;
      String wildcard = null;
      for (var entry : mapping.entrySet()) {
        if (!entry.getKey().startsWith("dimension.")) continue;
        String folder = normalize(entry.getKey().substring(10));
        for (String id : entry.getValue().split("\\s+")) {
          if (id.equals("*")) {
            if (wildcard != null && !wildcard.equals(folder))
              throw new ShaderPackException("Conflicting dimension wildcards");
            wildcard = folder;
          } else if (dimension.equals(id.contains(":") ? id : "minecraft:" + id)) {
            if (selected != null && !selected.equals(folder))
              throw new ShaderPackException("Conflicting dimension mapping for " + dimension);
            selected = folder;
          }
        }
      }
      return selected != null ? selected : wildcard != null ? wildcard : "";
    }
    if (directories.stream().noneMatch(name -> name.matches("world-?\\d+"))) return "";
    String legacy =
        switch (dimension) {
          case "minecraft:the_nether" -> "world-1";
          case "minecraft:the_end" -> "world1";
          default -> "world0";
        };
    // Cadence: a dimension without its own folder uses the root programs (as OptiFine and Iris do). Sildur's packs have
    // world-1/ and world1/ but keep the overworld at the root; "world0" gave the overworld no programs at all.
    return directories.contains(legacy) ? legacy : "";
  }

  public Optional<ShaderProgram> program(String directory, String name) throws ShaderPackException {
    String folder = normalize(directory);
    if (!name.matches("[A-Za-z_][A-Za-z_0-9]*"))
      throw new ShaderPackException("Invalid program name: " + name);
    Map<ShaderStage, String> paths = new EnumMap<>(ShaderStage.class);
    for (ShaderStage stage : ShaderStage.values()) {
      String path = (folder.isEmpty() ? "" : folder + "/") + name + "." + stage.extension();
      if (files.containsKey(path)) paths.put(stage, path);
    }
    return paths.isEmpty() ? Optional.empty() : Optional.of(new ShaderProgram(name, folder, paths));
  }

  public List<ShaderProgram> programs(String directory) throws ShaderPackException {
    String folder = normalize(directory);
    String prefix = folder.isEmpty() ? "" : folder + "/";
    Set<String> names = new TreeSet<>();
    for (String path : files.keySet()) {
      if (!path.startsWith(prefix)) continue;
      String relative = path.substring(prefix.length());
      if (relative.indexOf('/') >= 0) continue;
      for (ShaderStage stage : ShaderStage.values()) {
        String extension = "." + stage.extension();
        if (relative.endsWith(extension))
          names.add(relative.substring(0, relative.length() - extension.length()));
      }
    }
    List<ShaderProgram> result = new ArrayList<>();
    for (String name : names) result.add(program(folder, name).orElseThrow());
    return List.copyOf(result);
  }

  public String source(String path) throws ShaderPackException {
    return source(path, Map.of(), Map.of());
  }

  public String source(
      String path, Map<String, String> overrides, Map<String, String> injectedDefines)
      throws ShaderPackException {
    return expand(path, overrides, injectedDefines).text();
  }

  public record ExpandedSource(String text, Map<Integer, String> sourceFiles) {
    public ExpandedSource {
      sourceFiles = Map.copyOf(sourceFiles);
    }
  }

  public ExpandedSource expand(
      String path, Map<String, String> overrides, Map<String, String> injectedDefines)
      throws ShaderPackException {
    options.values(overrides);
    Map<String, Integer> ids = new LinkedHashMap<>();
    StringBuilder output = new StringBuilder();
    expandFile(normalize(path), overrides, new ArrayDeque<>(), ids, output);
    String source = output.toString();
    if (!injectedDefines.isEmpty()) {
      StringBuilder prelude = new StringBuilder();
      for (var entry : new TreeMap<>(injectedDefines).entrySet()) {
        if (!entry.getKey().matches("[A-Za-z_][A-Za-z_0-9]*")
            || entry.getValue().contains("\n")
            || entry.getValue().contains("\r")
            || entry.getValue().contains("#")) {
          throw new ShaderPackException("Invalid environment definition: " + entry.getKey());
        }
        prelude
            .append("#define ")
            .append(entry.getKey())
            .append(' ')
            .append(entry.getValue())
            .append('\n');
      }
      Matcher version = VERSION.matcher(source);
      if (!version.find())
        throw new ShaderPackException("Shader has no #version directive: " + path);
      int afterVersion = version.end();
      // Cadence: a file whose #version sits inside a conditional (Miniature: "#if MC_VERSION >= 1800" then "#version 120")
      // needs the definitions before that conditional, or it reads MC_VERSION as 0 and its whole program disappears. The
      // compiler drops every #version line and writes its own, so the top of the file is as good a place.
      if (LEADING_IF.matcher(source.substring(0, version.start())).find()) source = prelude + source;
      else source = source.substring(0, afterVersion) + "\n" + prelude + source.substring(afterVersion);
    }
    Map<Integer, String> sourceFiles = new TreeMap<>();
    ids.forEach((file, id) -> sourceFiles.put(id, file));
    return new ExpandedSource(source, sourceFiles);
  }

  private void expandFile(
      String path,
      Map<String, String> overrides,
      ArrayDeque<String> stack,
      Map<String, Integer> ids,
      StringBuilder output)
      throws ShaderPackException {
    if (stack.contains(path))
      throw new ShaderPackException("Include cycle: " + stack + " -> " + path);
    if (stack.size() >= MAX_INCLUDE_DEPTH)
      throw new ShaderPackException("Include nesting exceeds " + MAX_INCLUDE_DEPTH + " at " + path);
    int id = ids.computeIfAbsent(path, ignored -> ids.size());
    stack.push(path);
    String source = options.replace(path, text(path), overrides);
    String visible = ShaderOptions.withoutBlockComments(source);
    String[] lines = source.split("\n", -1);
    String[] visibleLines = visible.split("\n", -1);
    for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
      Matcher include = INCLUDE.matcher(visibleLines[lineNumber]);
      if (include.matches()) {
        String target = include.group(1);
        int lastSlash = path.lastIndexOf('/');
        String parent = lastSlash < 0 ? "" : path.substring(0, lastSlash + 1);
        String included = normalize(target.startsWith("/") ? target.substring(1) : parent + target);
        int childId = ids.computeIfAbsent(included, ignored -> ids.size());
        output.append("#line 1 ").append(childId).append('\n');
        expandFile(included, overrides, stack, ids, output);
        output.append("#line ").append(lineNumber + 2).append(' ').append(id).append('\n');
      } else {
        output.append(lines[lineNumber]).append('\n');
      }
      if (output.length() > MAX_EXPANDED_CHARS)
        throw new ShaderPackException("Expanded shader exceeds 16 MiB: " + path);
    }
    // Cadence: conditionals a file leaves open close at its end, as Iris's preprocessor closes them (Pegasus's
    // Better_3D_Textures leaves three open inside an #if POM / #else / #endif of the file including it; closing them only
    // at the very end put the rest of the shader, main included, in the false branch)
    int open = 0;
    for (String line : visibleLines) {
      Matcher directive = CONDITIONAL.matcher(line);
      if (!directive.matches()) continue;
      if (directive.group(1).equals("endif")) open = Math.max(0, open - 1);
      else open++;
    }
    for (int i = 0; i < open; i++) output.append("#endif\n");
    stack.pop();
  }

  private static final Pattern CONDITIONAL = Pattern.compile("[ \\t]*#[ \\t]*(if|ifdef|ifndef|endif)\\b.*");

  private static String normalize(String path) throws ShaderPackException {
    if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0 || path.indexOf(':') >= 0) {
      throw new ShaderPackException("Invalid pack path: " + path);
    }
    ArrayDeque<String> components = new ArrayDeque<>();
    for (String part : path.split("/")) {
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals("..")) {
        if (components.isEmpty())
          throw new ShaderPackException("Pack path escapes shaders directory: " + path);
        components.removeLast();
      } else components.addLast(part);
    }
    return String.join("/", components);
  }
}
