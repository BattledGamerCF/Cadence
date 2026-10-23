// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import dev.cadence.shaders.compile.ShaderCompatibilityCompiler;
import dev.cadence.shaders.compile.TranslatedProgram;
import dev.cadence.shaders.pack.ShaderDirectives;
import dev.cadence.shaders.pack.ShaderPack;
import dev.cadence.shaders.pack.ShaderPackException;
import dev.cadence.shaders.pack.ShaderProgram;
import dev.cadence.shaders.pack.ShaderProperties;
import dev.cadence.shaders.pack.ShaderStage;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.joml.Vector4f;

/** CPU-only program discovery and validation complete before allocating a new frame graph. */
public final class PackPrograms implements AutoCloseable {
  private static final boolean EARLY_ALPHA_DEMOTE =
      Boolean.getBoolean("cadence.shaders.earlyAlphaDemote");

  public record Source(
      String name,
      String vertex,
      String fragment,
      TranslatedProgram translated,
      ShaderDirectives directives,
      boolean relaxedFragmentMath) {
    /** External/test/derived sources do not inherit a pack's opt-in policy implicitly. */
    public Source(
        String name,
        String vertex,
        String fragment,
        TranslatedProgram translated,
        ShaderDirectives directives) {
      this(name, vertex, fragment, translated, directives, false);
    }
  }

  private static final Map<String, String> FALLBACK =
      Map.ofEntries(
          Map.entry("gbuffers_terrain", "gbuffers_textured_lit"),
          Map.entry("gbuffers_damagedblock", "gbuffers_terrain"),
          Map.entry("gbuffers_water", "gbuffers_terrain"),
          Map.entry("gbuffers_block", "gbuffers_terrain"),
          Map.entry("gbuffers_entities", "gbuffers_textured_lit"),
          Map.entry("gbuffers_entities_glowing", "gbuffers_entities"),
          Map.entry("gbuffers_entities_translucent", "gbuffers_entities"),
          Map.entry("gbuffers_hand", "gbuffers_textured_lit"),
          Map.entry("gbuffers_hand_water", "gbuffers_hand"),
          Map.entry("gbuffers_armor_glint", "gbuffers_textured"),
          Map.entry("gbuffers_textured_lit", "gbuffers_textured"),
          Map.entry("gbuffers_weather", "gbuffers_textured_lit"),
          Map.entry("gbuffers_clouds", "gbuffers_textured"),
          Map.entry("gbuffers_skytextured", "gbuffers_textured"),
          Map.entry("gbuffers_skybasic", "gbuffers_basic"),
          Map.entry("gbuffers_textured", "gbuffers_basic"),
          Map.entry("gbuffers_beaconbeam", "gbuffers_textured"));
  private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
  private static final Pattern UNIFORM_DECLARATION = Pattern.compile("\\buniform\\s+[^;]+;");
  /** The programs OptiFine and Iris run (Iris's ProgramId list); any other file name in a pack is ignored, as they do. */
  private static final Pattern KNOWN_PROGRAM =
      Pattern.compile(
          "gbuffers_(basic|line|textured|textured_lit|skybasic|skytextured|clouds|terrain|terrain_solid|terrain_cutout"
              + "|terrain_cutout_mip|damagedblock|block|block_translucent|beaconbeam|item|entities|entities_translucent"
              + "|entities_glowing|lightning|particles|particles_translucent|armor_glint|spidereyes|hand|hand_water"
              + "|weather|water)"
              + "|shadow(_solid|_cutout|_water|_entities|_lightning|_block)?"
              + "|(shadowcomp|prepare|deferred|composite|begin|setup)[0-9]*(?:_[a-z])?|final");
  private static final Pattern CENTER_DEPTH = Pattern.compile("\\bcenterDepthSmooth\\b");
  public final ShaderPack pack;
  public final Map<String, String> options, environment;
  public final ShaderProperties properties;
  public final String directory;
  public final ShaderDirectives globals;
  public final ShadowCullingEligibility.Result shadowCullingEligibility;
  /** Cadence (PackCheck): this pack's content, effective options and dimension, as the key of a pack check result. */
  public final String checkKey;
  public final boolean relaxedFragmentMath;
  private final Map<String, Source> sources = new HashMap<>();
  private final Set<String> samplers = new HashSet<>();
  private final Set<String> postSamplers = new HashSet<>();
  private final ShaderCompatibilityCompiler compiler;

  /**
   * Cadence (PackCensus): while set on this thread, every refusal is recorded here and discovery goes on with the next program,
   * so one run lists everything a pack needs that the loader lacks (normally the first refusal stops the load).
   */
  public static final ThreadLocal<List<String>> CENSUS = new ThreadLocal<>();

  /** Throws the refusal, or records it while a census runs (the caller then skips what it refused). */
  private static void refuse(RuntimeException refusal) {
    List<String> census = CENSUS.get();
    if (census == null) throw refusal;
    census.add(refusal.getMessage());
  }

  public PackPrograms(ShaderPack pack, Map<String, String> options, String dimension)
      throws ShaderPackException {
    this(pack, options, dimension, ShaderCompatibilityCompiler.ShadowComparison.EMULATED);
  }

  public PackPrograms(
      ShaderPack pack,
      Map<String, String> options,
      String dimension,
      ShaderCompatibilityCompiler.ShadowComparison shadowComparison)
      throws ShaderPackException {
    this.pack = pack;
    this.options = Map.copyOf(options);
    directory = pack.dimensionFolder(dimension);
    // Cadence: with far terrain running, a pack with a dh_terrain program draws it (DistantTerrain) and sees Iris's Distant
    // Horizons macros, so its other programs take their DH paths too (fog to the far terrain's reach, dhDepthTex)
    boolean distant = false;
    if (dev.cadence.shaders.cadence.render.DistantTerrain.available())
      for (ShaderProgram program : pack.programs(directory))
        distant |= program.name().equals("dh_terrain") && program.paths().containsKey(ShaderStage.VERTEX)
            && program.paths().containsKey(ShaderStage.FRAGMENT);
    distantTerrain = distant;
    Map<String, String> env = new java.util.LinkedHashMap<>(PackEnvironment.definitions());
    if (distant) env.putAll(dev.cadence.shaders.cadence.render.DistantTerrain.definitions());
    environment = env;
    properties = pack.properties(options, environment);
    Map<String, String> constants = new TreeMap<>();
    Map<String, String> definitions = pack.definitions(options, environment);
    Map<String, String> effectiveOptions = pack.optionValues(options);
    shadowCullingEligibility =
        ShadowCullingEligibility.assess(pack, options, dimension, environment);
    checkKey = dev.cadence.shaders.cadence.packcheck.PackCheck.key(pack.contentFingerprint(), dimension, effectiveOptions);
    // The same immutable content/defaults/environment identity was used for the BSL image audit.
    // This is a bounded experiment, not a policy inferred from a pack name or shader style.
    // Cadence: every pack's color programs compile with Metal's Relaxed math (reassociation and contraction allowed; Inf
    // and NaN still honoured, unlike Fast), what OpenGL drivers give GLSL anyway. Measured 10-14: Bliss 50 -> 22 ms GPU,
    // BSL 21 -> 14, Complementary 23.5 -> 15.7, Pegasus 89 -> 72, pictures alike. cadence.shaders.relaxedFragmentMath=false:
    // Safe math everywhere; =true: only the original bounded experiment (BSL-like packs).
    String relaxed = System.getProperty("cadence.shaders.relaxedFragmentMath", "all");
    relaxedFragmentMath =
        relaxed.equalsIgnoreCase("all")
            || relaxed.equalsIgnoreCase("true")
                && shadowCullingEligibility.eligible()
                && shadowComparison == ShaderCompatibilityCompiler.ShadowComparison.HARDWARE;
    for (var property : properties.values().entrySet()) {
      if (property.getKey().startsWith("size.buffer.") && bufferSize(property.getValue()) == null)
        refuse(unsupported("pack configuration", property.getKey() + " = " + property.getValue()));
    }
    // Validate properties/options before taking ownership of the native preprocessor.
    compiler = new ShaderCompatibilityCompiler(shadowComparison);
    this.shadowComparison = shadowComparison;
    storageContext = PackStorage.context(properties);
    compiler.storageContext(storageContext);
    try {
      List<Job> jobs = new java.util.ArrayList<>();
      for (ShaderProgram program : pack.programs(directory)) {
        boolean dh = distant && program.name().equals("dh_terrain");
        if (!dh && (program.name().startsWith("dh_") || program.name().startsWith("voxy"))) continue;
        // Cadence: only the program names OptiFine/Iris load. Packs ship others (disabled copies like I Like Vanilla's
        // "_gbuffers_entities_translucent", other mods' programs like Colorwheel's clrwl_*) that a loader never runs.
        if (!dh && !KNOWN_PROGRAM.matcher(program.name()).matches()) continue;
        if (!properties.programEnabled(program.identifier(), definitions)) continue;
        boolean computeOnly = program.name().matches(".*_[a-z]");
        if (program.paths().containsKey(ShaderStage.COMPUTE)) {
          // Cadence: an Iris compute program (prepare.csh, composite5_a.csh, ...): its own job; a fragment program of the
          // same name (composite5.vsh/.fsh) is a separate full-screen pass that runs after it
          jobs.add(new Job(program, pack.source(program.paths().get(ShaderStage.COMPUTE), options, environment), "", true, true, false));
          if (computeOnly || !program.paths().containsKey(ShaderStage.FRAGMENT)) continue;
        } else if (computeOnly) continue;
        // (shadowcomp: a full-screen pass over the shadow map after the shadow pass, ShaderRuntime.compileScreen)
        if (!validateProgramProperties(program)) continue;
        if (!program.paths().containsKey(ShaderStage.VERTEX)
            || !program.paths().containsKey(ShaderStage.FRAGMENT)) {
          if (CENSUS.get() == null)
            throw new ShaderPackException("Incomplete vertex/fragment pair: " + program.identifier());
          CENSUS.get().add("Incomplete vertex/fragment pair: " + program.identifier() + " " + program.paths().keySet());
          continue;
        }
        if (program.paths().size() - (program.paths().containsKey(ShaderStage.COMPUTE) ? 1 : 0) != 2) {
          // Cadence: said in words a player can act on (the pack list shows it): Metal has no geometry or tessellation stages
          var stages = program.paths().keySet();
          String what = stages.contains(ShaderStage.GEOMETRY) && (stages.contains(ShaderStage.TESS_CONTROL) || stages.contains(ShaderStage.TESS_EVALUATION))
              ? "geometry and tessellation shaders" : stages.contains(ShaderStage.GEOMETRY) ? "geometry shaders" : "tessellation shaders";
          refuse(new UnsupportedOperationException(
              "it needs " + what + ", which Metal doesn't have (" + program.identifier() + ")"));
          continue;
        }
        String vertex = pack.source(program.paths().get(ShaderStage.VERTEX), options, environment);
        String fragment =
            pack.source(program.paths().get(ShaderStage.FRAGMENT), options, environment);
        boolean screen =
            !dh && !program.name().startsWith("gbuffers_") && !program.name().equals("shadow");
        jobs.add(new Job(program, vertex, fragment, screen, false, dh));
      }
      // Cadence: the programs are translated in parallel (one shader compiler per worker; a pack's first load was ~9 s of
      // render thread for Complementary), then taken in the pack's order, so conflicts are found exactly as before.
      Object[] results = translateAll(jobs);
      for (int j = 0; j < jobs.size(); j++) {
        Job job = jobs.get(j);
        ShaderProgram program = job.program();
        String vertex = job.vertex(), fragment = job.fragment();
        if (results[j] instanceof RuntimeException failure && job.distant()) {
          // (the far terrain isn't drawn with this pack; the pack itself is fine)
          org.slf4j.LoggerFactory.getLogger("cadence_shaders").warn("Far terrain not drawn with this pack: {}", failure.getMessage());
          continue;
        }
        if (results[j] instanceof RuntimeException failure) {
          if (CENSUS.get() == null) throw failure;
          CENSUS.get().add("Translation failed in " + program.identifier() + ": " + failure.getMessage());
          continue;
        }
        TranslatedProgram translated = (TranslatedProgram) results[j];
        if (job.compute()) {
          // workGroups / workGroupsRender are this kernel's own; nothing of it joins the pack's global directives
          computes.put(program.name(), new Source(program.name(), vertex, "", translated,
              ShaderDirectives.parse(translated.preprocessedVertex()), false));
          continue;
        }
        try {
          validateFocusSupport(translated, effectiveOptions);
        } catch (UnsupportedOperationException refusal) {
          refuse(refusal);
        }
        for (var sampler : translated.samplers()) {
          samplers.add(sampler.name());
          if (program.name().equals("final") || program.name().matches("composite[0-9]*"))
            postSamplers.add(sampler.name());
        }
        ShaderDirectives v = ShaderDirectives.parse(translated.preprocessedVertex());
        ShaderDirectives f = ShaderDirectives.parse(translated.preprocessedFragment());
        Map<String, String> local = new TreeMap<>(v.constants());
        merge(local, f.constants());
        // MipmapEnabled describes input preparation for this pass; it is not a global toggle.
        for (var constant : local.entrySet()) {
          if (!constant.getKey().endsWith("MipmapEnabled"))
            merge(constants, Map.of(constant.getKey(), constant.getValue()));
        }
        sources.put(
            program.name(),
            new Source(
                program.name(),
                vertex,
                fragment,
                translated,
                new ShaderDirectives(translated.drawBuffers(), local),
                relaxedFragmentMath));
      }
      // Cadence: a pack without a final program (I Like Vanilla) shows colortex0, as OptiFine and Iris do.
      // -Dcadence.shaders.debugBuffer=N (self-test step debugbuffer=N): the final pass shows colortexN instead, for reading
      // what a pack's passes produce.
      String debugBuffer = System.getProperty("cadence.shaders.debugBuffer");
      if (!sources.containsKey("final") || debugBuffer != null) {
        // N: colortexN; Na: its alpha; any other sampler by name (depthtexN: red where depth is 1, green its curve)
        String requested = debugBuffer == null ? "0" : debugBuffer.strip();
        // nanN: colortexN dimmed, magenta where a channel is NaN or infinite (bit test: Relaxed math may drop isnan)
        boolean nan = requested.matches("nan\\d+");
        if (nan) requested = requested.substring(3);
        boolean alpha = requested.matches("\\d+a");
        String shown = requested.matches("\\d+a?") ? "colortex" + requested.replace("a", "") : requested;
        String view = alpha ? ".aaa" : ".rgb";
        String vertex =
            "#version 120\nvarying vec2 texcoord;\nvoid main() { gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.xy; }\n";
        String fragment = nan
            ? "#version 330\nuniform sampler2D " + shown + ";\nin vec2 texcoord;\n"
                + "bool bad(float x) { return (floatBitsToUint(x) & 0x7f800000u) == 0x7f800000u; }\n"
                + "void main() { vec4 c = texture(" + shown + ", texcoord);"
                + " gl_FragData[0] = (bad(c.r) || bad(c.g) || bad(c.b) || bad(c.a)) ? vec4(1.0, 0.0, 1.0, 1.0)"
                + " : vec4(clamp(c.rgb, 0.0, 1.0) * 0.6, 1.0); }\n"
            : "#version 120\nuniform sampler2D " + shown + ";\nvarying vec2 texcoord;\n"
                + (shown.startsWith("depthtex")
                    ? "void main() { float d = texture2D(" + shown + ", texcoord).r; gl_FragData[0] = vec4(d >= 1.0 ? 1.0 : 0.0, pow(d, 256.0), 0.0, 1.0); }\n"
                    : "void main() { gl_FragData[0] = vec4(texture2D(" + shown + ", texcoord)" + view + ", 1.0); }\n");
        TranslatedProgram translated =
            compiler.translate(vertex, fragment, directory + "/final (Cadence)", ShaderCompatibilityCompiler.VertexMode.FULLSCREEN);
        for (var sampler : translated.samplers()) {
          samplers.add(sampler.name());
          postSamplers.add(sampler.name());
        }
        sources.put("final", new Source("final", vertex, fragment, translated, new ShaderDirectives(List.of(0), Map.of())));
      }
      globals = new ShaderDirectives(List.of(), constants);
    } catch (Throwable failure) {
      compiler.close();
      throw failure;
    }
  }

  /** false: the program is refused as a whole (recorded by a census; otherwise thrown). */
  private record Job(ShaderProgram program, String vertex, String fragment, boolean screen, boolean compute, boolean distant) {}

  /** Cadence: whether this pack draws the far terrain (far terrain running, and the pack has a dh_terrain program). */
  public final boolean distantTerrain;

  public final ShaderCompatibilityCompiler.StorageContext storageContext;
  /** Cadence: the pack's compute programs by name (prepare, prepare_a, composite5_b, setup1, ...). */
  private final Map<String, Source> computes = new HashMap<>();

  public boolean hasCompute() {
    return !computes.isEmpty();
  }

  public java.util.Collection<Source> computeSources() {
    return java.util.Collections.unmodifiableCollection(computes.values());
  }

  /** A pass of a phase in Iris's order: a compute kernel, or a full-screen program. */
  public record Step(Source source, boolean compute) {}

  private final java.util.concurrent.ConcurrentHashMap<String, List<Step>> steps = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Cadence: phase's passes as Iris runs them: by index (phase, phase1, phase2, ...), for each the compute programs (phaseN,
   * then phaseN_a ... phaseN_z) and then the full-screen program phaseN.
   */
  public List<Step> steps(String phase) {
    return steps.computeIfAbsent(phase, this::findSteps);
  }

  private List<Step> findSteps(String phase) {
    Pattern name = Pattern.compile(Pattern.quote(phase) + "([0-9]*)(?:_([a-z]))?");
    TreeMap<Integer, TreeMap<String, Source>> byIndex = new TreeMap<>();
    for (Source c : computes.values()) {
      Matcher m = name.matcher(c.name());
      if (!m.matches()) continue;
      int index = m.group(1).isEmpty() ? 0 : Integer.parseInt(m.group(1));
      byIndex.computeIfAbsent(index, k -> new TreeMap<>()).put(m.group(2) == null ? "" : m.group(2), c);
    }
    java.util.List<Step> result = new java.util.ArrayList<>();
    java.util.Set<Integer> indices = new java.util.TreeSet<>(byIndex.keySet());
    for (Source f : sequence(phase))
      indices.add(f.name().equals(phase) ? 0 : Integer.parseInt(f.name().substring(phase.length())));
    for (int index : indices) {
      for (Source c : byIndex.getOrDefault(index, new TreeMap<>()).values()) result.add(new Step(c, true));
      Source f = sources.get(index == 0 ? phase : phase + index);
      if (f != null) result.add(new Step(f, false));
    }
    return List.copyOf(result);
  }

  private final ShaderCompatibilityCompiler.ShadowComparison shadowComparison;
  private static final boolean PARALLEL =
      !"false".equals(System.getProperty("cadence.shaders.parallelTranslation"));

  /** Cadence: set on a thread that makes PackPrograms in the background while the game runs: two low-priority workers. */
  static final ThreadLocal<Boolean> BACKGROUND = new ThreadLocal<>();

  /** Each job's TranslatedProgram, or the RuntimeException its translation threw, in job order. */
  private Object[] translateAll(List<Job> jobs) {
    Object[] results = new Object[jobs.size()];
    boolean background = BACKGROUND.get() != null;
    int workers = !PARALLEL ? 1
        : Math.min(jobs.size(), background ? 2 : Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    if (workers <= 1) {
      for (int j = 0; j < jobs.size(); j++) results[j] = translate(compiler, jobs.get(j));
      return results;
    }
    java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
    Thread[] threads = new Thread[workers];
    for (int w = 0; w < workers; w++) {
      threads[w] =
          Thread.ofPlatform()
              .name("cadence-pack-translate-" + w)
              .priority(background ? Thread.MIN_PRIORITY : Thread.NORM_PRIORITY)
              .start(
                  () -> {
                    try (var own = new ShaderCompatibilityCompiler(shadowComparison)) {
                      own.storageContext(storageContext);
                      for (int j; (j = next.getAndIncrement()) < jobs.size(); )
                        results[j] = translate(own, jobs.get(j));
                    }
                  });
    }
    for (Thread t : threads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while translating a shader pack", e);
      }
    }
    for (int j = 0; j < results.length; j++)
      if (results[j] == null) results[j] = translate(compiler, jobs.get(j)); // a worker died: here instead
    return results;
  }

  /**
   * Cadence: tasks run on worker threads, each worker with its own shader compiler set up as this pack's (as translateAll),
   * waiting for all; a task that throws is skipped (its own first use reports it). For translations ahead of use
   * (ShaderRuntime's warm-up of the game pipelines packs drew with): one compiler did them one at a time.
   */
  public void withCompilers(List<java.util.function.Consumer<ShaderCompatibilityCompiler>> tasks) {
    if (tasks.isEmpty()) return;
    int workers = !PARALLEL ? 1 : Math.min(tasks.size(), Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
    Runnable work = () -> {
      try (var own = new ShaderCompatibilityCompiler(shadowComparison)) {
        own.storageContext(storageContext);
        for (int j; (j = next.getAndIncrement()) < tasks.size(); ) {
          try {
            tasks.get(j).accept(own);
          } catch (RuntimeException failure) {
            // reported at first use
          }
        }
      }
    };
    Thread[] threads = new Thread[workers];
    for (int w = 0; w < workers; w++) threads[w] = Thread.ofPlatform().name("cadence-pack-translate-" + w).start(work);
    for (Thread t : threads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static Object translate(ShaderCompatibilityCompiler compiler, Job job) {
    try {
      if (job.compute()) return compiler.translateCompute(job.vertex(), job.program().identifier());
      if (job.distant())
        return compiler.translate(
            job.vertex(), job.fragment(), job.program().identifier(),
            dev.cadence.shaders.compile.MinecraftVertexAdapter.distantTerrain());
      return compiler.translate(
          job.vertex(),
          job.fragment(),
          job.program().identifier(),
          job.screen()
              ? ShaderCompatibilityCompiler.VertexMode.FULLSCREEN
              : ShaderCompatibilityCompiler.VertexMode.GEOMETRY);
    } catch (RuntimeException failure) {
      return failure;
    }
  }

  private boolean validateProgramProperties(ShaderProgram program) {
    if (program.name().matches("setup[0-9]*")) {
      refuse(unsupported(program.identifier(), "setup passes"));
      return false;
    }
    for (String property : properties.values().keySet()) {
      // (flip.PASS.BUFFER is honoured: ShaderRuntime.flipsAfter)
      if (property.equals("scale." + program.name())
          || property.equals("scale." + program.identifier())) {
        refuse(unsupported(program.identifier(), property));
      }
    }
    return true;
  }

  private static UnsupportedOperationException unsupported(String program, String declaration) {
    return new UnsupportedOperationException(
        "Shader pack "
            + program
            + " requires unsupported rendering declaration "
            + declaration
            + ". This loader cannot preserve that declaration's rendering behavior.");
  }

  /**
   * Do not advertise BSL's host-driven autofocus while no asynchronous center-depth reader exists.
   */
  static void validateFocusSupport(TranslatedProgram translated, Map<String, String> options) {
    if (!"true".equals(options.get("DOF")) || !"0".equals(options.get("DOF_FOCUS_MODE"))) return;
    if (usesCenterDepth(translated.preprocessedVertex())
        || usesCenterDepth(translated.preprocessedFragment())) {
      throw new UnsupportedOperationException(
          "Program "
              + translated.label()
              + " requires centerDepthSmooth autofocus, which this loader does not yet supply. "
              + "Set DOF_FOCUS_MODE=1 for the pack's depth-texture focus point, or disable DOF.");
    }
  }

  private static boolean usesCenterDepth(String preprocessed) {
    String code = COMMENTS.matcher(preprocessed).replaceAll("");
    code = UNIFORM_DECLARATION.matcher(code).replaceAll("");
    return CENTER_DEPTH.matcher(code).find();
  }

  /**
   * Cadence: a setting two programs declare differently (Nostalgia: shadowHardwareFiltering true in one, false in another)
   * takes the later program's value, as Iris's directive holder does (each declaration it reads replaces the last); it used
   * to refuse the pack.
   */
  private static void merge(Map<String, String> into, Map<String, String> values)
      throws ShaderPackException {
    for (var entry : values.entrySet()) {
      String existing = into.put(entry.getKey(), entry.getValue());
      if (existing != null && !existing.equals(entry.getValue()))
        org.slf4j.LoggerFactory.getLogger("cadence_shaders").info("Pack setting {} declared both {} and {}; using {}",
            entry.getKey(), existing, entry.getValue(), entry.getValue());
    }
  }

  /**
   * Cadence: size.buffer.colortexN's value: "0.5 0.5" (a fraction of the screen, OptiFine's rule: a decimal point) or
   * "480 270" (pixels); null if malformed.
   */
  static float[] bufferSize(String value) {
    String[] parts = value.trim().split("\\s+");
    if (parts.length != 2) return null;
    try {
      float w = Float.parseFloat(parts[0]), h = Float.parseFloat(parts[1]);
      if (!(w > 0) || !(h > 0) || !Float.isFinite(w) || !Float.isFinite(h)) return null;
      boolean relative = parts[0].contains(".") || parts[1].contains(".");
      return new float[] {w, h, relative ? 1 : 0};
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public Source find(String name) {
    return sources.get(name);
  }

  /** Cadence (PackTextures.setSamplerTypes): every program's samplers by name, with the GLSL type it declares them as. */
  public Map<String, Map<String, String>> samplerTypes() {
    Map<String, Map<String, String>> out = new HashMap<>();
    for (Map<String, Source> group : List.of(sources, computes))
      for (Source s : group.values()) {
        Map<String, String> types = new HashMap<>();
        for (var sampler : s.translated().samplers()) types.put(sampler.name(), sampler.type());
        out.put(s.name(), types);
      }
    return out;
  }

  public boolean readsSampler(String name) {
    return samplers.contains(name);
  }

  public boolean postReadsSampler(String name) {
    return postSamplers.contains(name);
  }

  public Source resolve(String name) {
    Set<String> visited = new HashSet<>();
    boolean shadow = name.startsWith("shadow");
    while (name != null && visited.add(name)) {
      Source source = sources.get(name);
      if (source != null) return source;
      name = FALLBACK.get(name);
    }
    if (shadow) throw new UnsupportedOperationException("Pack has no applicable shadow program: " + visited);
    return vanillaGeometry();
  }

  private Source vanilla;

  /**
   * Cadence: geometry the pack has no program for, even down its fallback chain (Miniature has no gbuffers_basic,
   * gbuffers_textured or gbuffers_skytextured), drawn as OptiFine and Iris draw it: the game's own look (its texture times
   * the vertex colour and the light map) into colortex0. It used to refuse the pack. A draw without light-map coordinates
   * (0, 0: the sun, the moon) isn't darkened.
   */
  private synchronized Source vanillaGeometry() {
    if (vanilla != null) return vanilla;
    String vertex =
        "#version 120\nvarying vec2 texcoord;\nvarying vec2 lmcoord;\nvarying vec4 glcolor;\nvarying float lit;\n"
            + "void main() {\n  gl_Position = ftransform();\n  texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;\n"
            + "  lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;\n  lit = gl_MultiTexCoord1.x + gl_MultiTexCoord1.y > 0.0 ? 1.0 : 0.0;\n"
            + "  glcolor = gl_Color;\n}\n";
    String fragment =
        "#version 120\nuniform sampler2D gtexture;\nuniform sampler2D lightmap;\nvarying vec2 texcoord;\nvarying vec2 lmcoord;\n"
            + "varying vec4 glcolor;\nvarying float lit;\n/* DRAWBUFFERS:0 */\nvoid main() {\n"
            + "  vec4 light = lit > 0.5 ? texture2D(lightmap, lmcoord) : vec4(1.0);\n"
            + "  gl_FragData[0] = texture2D(gtexture, texcoord) * glcolor * light;\n}\n";
    TranslatedProgram translated = compiler.translate(vertex, fragment, directory + "/gbuffers_basic (Cadence)");
    vanilla = new Source("gbuffers_basic", vertex, fragment, translated, new ShaderDirectives(List.of(0), Map.of()));
    return vanilla;
  }

  // Cadence: asked every frame per phase (composite, deferred, ...); the regex below compiled once per program per call
  private final java.util.concurrent.ConcurrentHashMap<String, List<Source>> sequences =
      new java.util.concurrent.ConcurrentHashMap<>();

  public List<Source> sequence(String prefix) {
    return sequences.computeIfAbsent(prefix, this::findSequence);
  }

  private List<Source> findSequence(String prefix) {
    return sources.values().stream()
        .filter(source -> source.name().matches(prefix + "[0-9]*"))
        .sorted(
            Comparator.comparingInt(
                source ->
                    source.name().equals(prefix)
                        ? 0
                        : Integer.parseInt(source.name().substring(prefix.length()))))
        .toList();
  }

  public TranslatedProgram geometry(
      Source source, ShaderCompatibilityCompiler.VertexAdapter adapter) {
    return compiler.translate(
        source.vertex(), source.fragment(), directory + "/" + source.name(), adapter);
  }

  public TranslatedProgram geometry(
      Source source,
      ShaderCompatibilityCompiler.VertexAdapter adapter,
      dev.cadence.shaders.pack.AlphaTestPolicy alphaTest) {
    return geometry(compiler, source, adapter, alphaTest);
  }

  /** Cadence: as above, with another compiler set up as this pack's (withCompilers): the same result and cache entry. */
  public TranslatedProgram geometry(
      ShaderCompatibilityCompiler with,
      Source source,
      ShaderCompatibilityCompiler.VertexAdapter adapter,
      dev.cadence.shaders.pack.AlphaTestPolicy alphaTest) {
    return with.translate(
        source.vertex(),
        source.fragment(),
        directory + "/" + source.name(),
        adapter,
        alphaTest,
        EARLY_ALPHA_DEMOTE
            && shadowCullingEligibility.eligible()
            && source.name().equals("gbuffers_terrain"));
  }

  /**
   * Cadence: the shadow color buffers' settings (OptiFine's shadowcolorN directives): format (RGBA8 by default), whether
   * and to what each frame clears them (white by default), mipmaps (shadowColorNMipmap, or generateShadowColorMipmap for
   * all) and nearest filtering.
   */
  public Map<Integer, PackRenderTargets.ShadowSpec> shadowSpecifications() throws ShaderPackException {
    Map<Integer, PackRenderTargets.ShadowSpec> result = new TreeMap<>();
    Map<Integer, String> formats = globals.shadowColorValues("Format"),
        clear = globals.shadowColorValues("Clear"),
        clearColor = globals.shadowColorValues("ClearColor"),
        mipmap = globals.shadowColorValues("Mipmap"),
        nearest = globals.shadowColorValues("Nearest");
    boolean allMipmaps = globals.booleanConstant("generateShadowColorMipmap", false);
    for (int index = 0; index < 8; index++) {
      List<Float> color =
          clearColor.containsKey(index) ? ShaderDirectives.clearColorDirective(clearColor.get(index)) : null;
      result.put(
          index,
          new PackRenderTargets.ShadowSpec(
              PackFormats.color(formats.getOrDefault(index, "RGBA8")),
              !clear.containsKey(index) || ShaderDirectives.booleanDirective(clear.get(index)),
              color == null ? new Vector4f(1) : new Vector4f(color.get(0), color.get(1), color.get(2), color.get(3)),
              allMipmaps || mipmap.containsKey(index) && ShaderDirectives.booleanDirective(mipmap.get(index)),
              nearest.containsKey(index) && ShaderDirectives.booleanDirective(nearest.get(index))));
    }
    return Map.copyOf(result);
  }

  public Map<Integer, PackRenderTargets.BufferSpec> bufferSpecifications()
      throws ShaderPackException {
    Map<Integer, String> formats = globals.bufferFormats();
    Map<Integer, Boolean> clear = globals.bufferClear();
    Map<Integer, List<Float>> clearColors = globals.bufferClearColors();
    Set<Integer> mipmaps = new HashSet<>();
    Map<Integer, float[]> sizes = new HashMap<>();
    for (var property : properties.values().entrySet()) {
      if (!property.getKey().startsWith("size.buffer.")) continue;
      float[] size = bufferSize(property.getValue());
      if (size != null) sizes.put(ShaderDirectives.colorBufferIndex(property.getKey().substring(12)), size);
    }
    Set<Integer> used = new HashSet<>();
    for (Source source : sources.values()) {
      mipmaps.addAll(source.directives().mipmapBuffers());
      used.addAll(source.translated().drawBuffers());
      for (var sampler : source.translated().samplers()) {
        if (sampler.name().matches("colortex[0-9]+|gcolor|gdepth|gnormal|composite|gaux[1-4]"))
          used.add(ShaderDirectives.colorBufferIndex(sampler.name()));
      }
    }
    used.remove(-1);
    Map<Integer, PackRenderTargets.BufferSpec> result = new TreeMap<>();
    for (int index : used) {
      List<Float> color = clearColors.get(index);
      result.put(
          index,
          new PackRenderTargets.BufferSpec(
              PackFormats.color(formats.getOrDefault(index, "RGBA8")),
              clear.getOrDefault(index, true),
              color == null
                  ? null
                  : new Vector4f(color.get(0), color.get(1), color.get(2), color.get(3)),
              mipmaps.contains(index),
              sizes.get(index)));
    }
    return Map.copyOf(result);
  }

  /** Cadence: the indices of colorimgN (shadow: shadowcolorimgN) images any of the pack's programs declares. */
  public Set<Integer> storageImages(boolean shadow) {
    java.util.regex.Pattern name = java.util.regex.Pattern.compile(shadow ? "shadowcolorimg([0-9]+)" : "colorimg([0-9]+)");
    Set<Integer> result = new HashSet<>();
    List<Source> all = new java.util.ArrayList<>(sources.values());
    all.addAll(computes.values());
    for (Source source : all)
      for (var r : source.translated().storage()) {
        var m = name.matcher(r.name());
        if (m.matches()) result.add(Integer.parseInt(m.group(1)));
      }
    return result;
  }

  @Override
  public void close() {
    compiler.close();
  }
}
