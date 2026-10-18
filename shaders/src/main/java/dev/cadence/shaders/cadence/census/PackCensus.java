package dev.cadence.shaders.cadence.census;

import dev.cadence.shaders.pack.CustomUniforms;
import dev.cadence.shaders.pack.ShaderPack;
import dev.cadence.shaders.pack.ShaderProgram;
import dev.cadence.shaders.pack.ShaderProperties;
import dev.cadence.shaders.pack.ShaderStage;
import dev.cadence.shaders.runtime.PackEnvironment;
import dev.cadence.shaders.runtime.PackPrograms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cadence Pack Census: what each shader pack needs that the loader can't do yet, without starting Minecraft. For every pack
 * and dimension (and quality profile), the loader's own discovery and GLSL translation run with refusals recorded instead of
 * thrown (PackPrograms.CENSUS), so one run lists every blocker, not only the first. The GPU side (Metal compile, drawing) is
 * not covered: a pack that passes here still has to be looked at in the game.
 *
 * <p>usage: java -cp ... dev.cadence.shaders.cadence.census.PackCensus [--profiles] pack.zip|folder ...
 */
public final class PackCensus {
  private static final String[] DIMENSIONS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};

  private PackCensus() {
  }

  public static void main(String[] args) throws Exception {
    boolean profiles = false;
    List<Path> packs = new ArrayList<>();
    for (String arg : args) {
      if (arg.equals("--profiles")) profiles = true;
      else if (Files.isDirectory(Path.of(arg)) && !Files.isDirectory(Path.of(arg, "shaders"))) {
        try (var list = Files.list(Path.of(arg))) {
          list.filter(p -> p.toString().endsWith(".zip") || Files.isDirectory(p)).sorted().forEach(packs::add);
        }
      } else packs.add(Path.of(arg));
    }
    Map<String, Integer> blockerCounts = new TreeMap<>();
    for (Path path : packs) {
      System.out.println("\n=== " + path.getFileName());
      ShaderPack pack;
      try {
        pack = ShaderPack.load(path);
      } catch (Exception e) {
        System.out.println("  LOAD FAILED: " + e);
        continue;
      }
      inventory(pack);
      List<String> variants = new ArrayList<>();
      variants.add("");
      if (profiles) variants.addAll(profileNames(pack));
      Set<String> packBlockers = new LinkedHashSet<>();
      for (String profile : variants) {
        Map<String, String> options = profile.isEmpty() ? Map.of() : pack.profileOverrides(profile);
        for (String dimension : DIMENSIONS) {
          List<String> problems = new ArrayList<>();
          long t0 = System.nanoTime();
          int programs = check(pack, options, dimension, problems);
          long ms = (System.nanoTime() - t0) / 1_000_000;
          String label = (profile.isEmpty() ? "default" : "profile " + profile) + " / " + dimension.substring(10);
          // notes, not blockers: uniforms Cadence doesn't supply read zero, as an unset uniform does in OpenGL
          List<String> notes = problems.stream().filter(p -> p.startsWith("Uniforms Cadence")).toList();
          problems.removeAll(notes);
          for (String note : notes) System.out.println("  note    " + label + ": " + note);
          if (problems.isEmpty()) System.out.println("  OK      " + label + " (" + programs + " programs, " + ms + " ms)");
          else {
            System.out.println("  BLOCKED " + label + " (" + programs + " programs translated, " + ms + " ms):");
            for (String p : new LinkedHashSet<>(problems)) {
              System.out.println("      - " + p);
              packBlockers.add(category(p));
            }
          }
        }
      }
      for (String b : packBlockers) blockerCounts.merge(b, 1, Integer::sum);
    }
    System.out.println("\n=== Blockers by number of packs");
    blockerCounts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
        .forEach(e -> System.out.println("  " + e.getValue() + "  " + e.getKey()));
  }

  /** The loader's CPU-side load of one pack, dimension and options, refusals recorded: the number of programs translated. */
  static int check(ShaderPack pack, Map<String, String> options, String dimension, List<String> problems) {
    PackPrograms.CENSUS.set(problems);
    try {
      var environment = PackEnvironment.definitions();
      ShaderProperties properties = pack.properties(options, environment);
      try {
        CustomUniforms.compile(properties);
      } catch (Exception e) {
        problems.add("Custom uniforms: " + e.getMessage());
      }
      for (var property : properties.values().entrySet()) {
        if (property.getKey().startsWith("texture.") && property.getValue().contains(" ")) {
          try {
            var raw = dev.cadence.shaders.runtime.PackTextures.parseRaw(property.getValue());
            pack.bytes(raw.path());
          } catch (Exception e) {
            problems.add("Raw custom texture declaration: " + property.getKey() + " = " + property.getValue() + ": " + e.getMessage());
          }
        }
      }
      // (hardware shadow comparison, as Cadence's Metal device offers and the game uses: sampler2DShadow kept)
      try (PackPrograms programs = new PackPrograms(pack, options, dimension,
          dev.cadence.shaders.compile.ShaderCompatibilityCompiler.ShadowComparison.HARDWARE)) {
        try {
          var specs = programs.bufferSpecifications();
          if (DUMP != null) System.out.println("  buffers " + programs.directory + ": " + new java.util.TreeMap<>(specs));
        } catch (Exception e) {
          problems.add("Buffers: " + e.getMessage());
        }
        int n = 0;
        java.util.Set<String> unsupplied = new java.util.TreeSet<>();
        for (ShaderProgram p : pack.programs(programs.directory)) {
          PackPrograms.Source source = programs.find(p.name());
          if (source == null) continue;
          n++;
          spirv(source.translated(), problems);
          for (var field : source.translated().uniforms().fields()) {
            String name = field.name();
            if (name.startsWith("sl_") || SUPPLIED.contains(name) || name.startsWith("BIOME_")) continue;
            if (properties.values().containsKey("uniform." + field.type() + "." + name)) continue;
            if (source.translated().uniforms().defaults().containsKey(name)) continue;
            unsupplied.add(name + " (" + field.type() + ")");
          }
        }
        for (PackPrograms.Source compute : programs.computeSources()) {
          n++;
          // the kernel's GLSL to SPIR-V and on to Metal source (SPIRV-Cross; no GPU needed)
          java.nio.ByteBuffer spirv = null;
          try {
            spirv = dev.cadence.shaders.runtime.PackCompute.spirv(compute.translated().vertexSource(), compute.translated().label());
            List<dev.cadence.metal.MetalCompute.SamplerState> states = new java.util.ArrayList<>();
            for (int i = 0; i < compute.translated().samplers().size(); i++)
              states.add(new dev.cadence.metal.MetalCompute.SamplerState(false, false, false, false));
            List<dev.cadence.metal.MetalStorage.Slot> slots = new java.util.ArrayList<>();
            for (var r : compute.translated().storage())
              slots.add(new dev.cadence.metal.MetalStorage.Slot(r.binding(),
                  r.kind() == dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.BUFFER,
                  r.kind() == dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.SAMPLED, false, () -> 0L));
            String[] msl = dev.cadence.metal.MetalCompute.translateKernel(spirv, states, slots);
            if (METAL) {
              String metal = dev.cadence.metal.MetalCompute.check(spirv, 2);
              if (metal != null) problems.add("Metal " + compute.translated().label() + " kernel: " + metal);
            }
            if (DUMP != null) Files.writeString(DUMP.resolve(compute.translated().label().replaceAll("[^A-Za-z0-9._-]", "_") + ".metal"), msl[0]);
          } catch (Exception e) {
            problems.add("Compute " + compute.translated().label() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
          } finally {
            if (spirv != null) org.lwjgl.system.MemoryUtil.memFree(spirv);
          }
          if (DUMP != null) {
            try {
              Files.createDirectories(DUMP);
              Files.writeString(DUMP.resolve(compute.translated().label().replaceAll("[^A-Za-z0-9._-]", "_") + ".csh"),
                  compute.translated().vertexSource());
            } catch (java.io.IOException ignored) {
              // reading aid only
            }
          }
        }
        if (!unsupplied.isEmpty()) problems.add("Uniforms Cadence doesn't supply (zero): " + unsupplied);
        return n;
      }
    } catch (Throwable e) {
      problems.add("Load: " + e);
      return 0;
    } finally {
      PackPrograms.CENSUS.remove();
    }
  }

  /**
   * The game's GLSL to SPIR-V step on a translated program, with the options Mojang's GlslCompiler uses (Vulkan 1.2 target,
   * auto-bound uniforms, depth 0..1 as on Metal), so errors the GPU compile would hit in the game show up here. Geometry
   * programs are checked as translated for no particular vertex format (the game adapts them per draw type).
   */
  /** -Dcadence.census.metal=false: stop at SPIR-V (no Metal compile). */
  private static final boolean METAL = !"false".equals(System.getProperty("cadence.census.metal"));
  private static final Path DUMP =
      System.getProperty("cadence.census.dump") == null ? null : Path.of(System.getProperty("cadence.census.dump"));

  static void spirv(dev.cadence.shaders.compile.TranslatedProgram program, List<String> problems) {
    long compiler = org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize();
    long options = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize();
    try {
      org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
      org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
      org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_add_macro_definition(options, "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
      for (int stage = 0; stage < 2; stage++) {
        String text = stage == 0 ? program.vertexSource() : program.fragmentSource();
        if (DUMP != null) { // -Dcadence.census.dump=DIR: every translated stage, for reading
          try {
            java.nio.file.Files.createDirectories(DUMP);
            java.nio.file.Files.writeString(
                DUMP.resolve(program.label().replaceAll("[^A-Za-z0-9._-]", "_") + (stage == 0 ? ".vsh" : ".fsh")),
                stage == 0 ? text : "// drawBuffers " + program.drawBuffers() + "\n" + text);
          } catch (java.io.IOException ignored) {
            // a reading aid only
          }
        }
        // heap buffers: a pack's source can be far larger than LWJGL's per-thread stack (the String overloads use it)
        var sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(text, false);
        var name = org.lwjgl.system.MemoryUtil.memUTF8("pack");
        var entry = org.lwjgl.system.MemoryUtil.memUTF8("main");
        long result =
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(
                compiler, sourceBytes,
                stage == 0 ? org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader : org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader,
                name, entry, options);
        org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
        org.lwjgl.system.MemoryUtil.memFree(name);
        org.lwjgl.system.MemoryUtil.memFree(entry);
        try {
          if (org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(result)
              == org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success && METAL) {
            // on through SPIRV-Cross and Metal's compiler (no window needed)
            String metal = dev.cadence.metal.MetalCompute.check(org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes(result), stage);
            if (metal != null) problems.add("Metal " + program.label() + (stage == 0 ? " vertex: " : " fragment: ") + metal);
          }
          if (org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(result)
              != org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success) {
            String message = String.valueOf(org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(result));
            int at = message.indexOf("error:");
            String line = message.substring(Math.max(0, at)).lines().findFirst().orElse(message);
            problems.add("SPIR-V " + program.label() + (stage == 0 ? " vertex: " : " fragment: ") + line + context(text, message));
          }
        } finally {
          org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result);
        }
      }
    } finally {
      org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release(options);
      org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler);
    }
  }

  /** The source line an error names ("pack:123: error: ..."), for reading. */
  private static String context(String text, String message) {
    var m = java.util.regex.Pattern.compile(":(\\d+): error").matcher(message);
    if (!m.find()) return "";
    int n = Integer.parseInt(m.group(1));
    String[] lines = text.split("\n", -1);
    return n >= 1 && n <= lines.length ? "  [line: " + lines[n - 1].strip() + "]" : "";
  }

  /**
   * The uniform names FrameUniforms supplies: every string literal in its source (a census of a dev checkout; the census
   * isn't run from a release).
   */
  private static final java.util.Set<String> SUPPLIED = supplied();

  private static java.util.Set<String> supplied() {
    java.util.Set<String> names = new java.util.HashSet<>();
    try {
      for (String file : List.of("runtime/FrameUniforms.java", "runtime/ShaderRuntime.java", "runtime/ShadowRenderer.java")) {
        Path path = Path.of(System.getProperty("cadence.census.src",
            "shaders/src/main/java/dev/cadence/shaders"), file);
        var m = java.util.regex.Pattern.compile("\"([A-Za-z_][A-Za-z_0-9]*)\"").matcher(Files.readString(path));
        while (m.find()) names.add(m.group(1));
      }
    } catch (Exception e) {
      // no sources: every uniform is reported
    }
    return names;
  }

  /** What the pack contains regardless of options: programs by stage and the GL features its sources mention. */
  private static void inventory(ShaderPack pack) {
    Map<String, Integer> stages = new LinkedHashMap<>();
    Map<String, Integer> features = new TreeMap<>();
    String[][] probes = {
      {"image load/store", "imageLoad|imageStore|image2D|image3D|uimage"},
      {"SSBO", "layout\\s*\\([^)]*std430|\\bbuffer\\s+\\w+\\s*\\{"},
      {"atomics", "atomicAdd|imageAtomic|atomicMax|atomicMin"},
      {"compute (workGroups)", "workGroups|local_size_x"},
      {"IS_IRIS guard", "IS_IRIS|IRIS_VERSION"},
      {"MC_GL_VERSION >= 4xx", "MC_GL_VERSION\\s*>=?\\s*4\\d0"},
      {"Distant Horizons", "DISTANT_HORIZONS|dhProjection"},
      {"customTexture/raw", "TEXTURE_3D|TEXTURE_2D"},
    };
    for (String file : pack.files()) {
      for (ShaderStage stage : ShaderStage.values())
        if (file.endsWith("." + stage.extension())) stages.merge(stage.extension(), 1, Integer::sum);
      if (!file.matches(".*\\.(vsh|fsh|csh|gsh|tcs|tes|glsl|properties)")) continue;
      String text;
      try {
        text = pack.text(file);
      } catch (Exception e) {
        continue;
      }
      for (String[] probe : probes)
        if (java.util.regex.Pattern.compile(probe[1]).matcher(text).find()) features.merge(probe[0], 1, Integer::sum);
    }
    System.out.println("  files by stage " + stages + "; files mentioning " + features);
  }

  private static List<String> profileNames(ShaderPack pack) {
    List<String> names = new ArrayList<>();
    try {
      for (String key : pack.properties(Map.of(), PackEnvironment.definitions()).values().keySet())
        if (key.startsWith("profile.")) names.add(key.substring(8));
    } catch (Exception e) {
      // no profiles
    }
    return names;
  }

  /** A blocker's kind, for counting packs per missing feature. */
  static String category(String problem) {
    if (problem.contains("compute-capable")) return "compute programs";
    if (problem.startsWith("Additional shader stage")) return "geometry/tessellation stages";
    if (problem.contains("setup passes")) return "setup passes";
    if (problem.contains("declaration scale.")) return "scale.<program>";
    if (problem.contains("declaration flip.")) return "flip.<program>";
    if (problem.contains("size.buffer")) return "size.buffer";
    if (problem.matches(".*shadow[Cc]olor\\d+\\w*\\..*") || problem.contains("generateShadowColorMipmap"))
      return "shadowcolor directives";
    if (problem.startsWith("Raw custom texture")) return "raw custom textures";
    if (problem.contains("color format")) return "colour formats";
    if (problem.contains("centerDepthSmooth")) return "centerDepthSmooth";
    if (problem.startsWith("Custom uniforms")) return "custom uniforms";
    if (problem.startsWith("Translation failed")) return "translation errors";
    if (problem.startsWith("SPIR-V")) return "GLSL compile errors";
    if (problem.startsWith("Metal")) return "Metal compile errors";
    if (problem.startsWith("Uniforms Cadence")) return "unsupplied uniforms";
    if (problem.startsWith("Load:")) return "load errors";
    return problem.length() > 60 ? problem.substring(0, 60) : problem;
  }
}
