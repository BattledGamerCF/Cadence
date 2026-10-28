// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cadence.metal.MetalPassHints;
import dev.cadence.shaders.compile.MatrixUniformSpecialization;
import dev.cadence.shaders.compile.MinecraftVertexAdapter;
import dev.cadence.shaders.compile.ShaderCompatibilityCompiler;
import dev.cadence.shaders.compile.UniformLayout;
import dev.cadence.shaders.geometry.FeatureDrawContext;
import dev.cadence.shaders.pack.AlphaTestPolicy;
import dev.cadence.shaders.pack.CustomUniforms;
import dev.cadence.shaders.pack.ShaderPack;
import dev.cadence.shaders.pack.ShaderProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4fc;
import org.slf4j.LoggerFactory;

/** Owns the pack graph for one game instance. All GPU methods run on the render thread. */
public final class ShaderRuntime implements AutoCloseable {
  // Cadence: changeable at run time (self-test A/B; Video Settings later), not only at launch.
  /** Cadence: -Dcadence.shaders.opaqueUntested=false: the old rule (every draw without a cutout define tested at 0.1). */
  private static final boolean OPAQUE_UNTESTED =
      Boolean.parseBoolean(System.getProperty("cadence.shaders.opaqueUntested", "true"));

  private static volatile boolean DISCARD_FULLSCREEN_LOADS =
      Boolean.getBoolean("cadence.shaders.discardFullscreenLoads");

  public static void setDiscardFullscreenLoads(boolean on) {
    DISCARD_FULLSCREEN_LOADS = on;
  }

  public static boolean discardFullscreenLoads() {
    return DISCARD_FULLSCREEN_LOADS;
  }
  private static final boolean SPARSE_PROJECTION =
      Boolean.getBoolean("cadence.shaders.sparseProjectionMatrices");
  private static volatile ShaderRuntime instance;  // (read by chunk-compile workers and the pack-switch worker too)
  // Cadence render scale: the pack draws its whole frame at scale x the window into scaledOutput, then PackUpscale draws
  // that to the window. Null at scale 1 (exactly the original path).
  private com.mojang.renderpearl.api.textures.GpuTexture scaledOutput;
  private com.mojang.renderpearl.api.textures.GpuTextureView scaledOutputView;
  private PackUpscale upscale;
  private int frameWidth, frameHeight;
  private final ShaderPack pack;
  private final Map<String, String> options;
  private final ShaderProperties properties;
  private final MaterialPalette materialPalette;
  private final FrameUniforms uniforms = new FrameUniforms();
  private final ShaderFrameProfiler profiler = ShaderFrameProfiler.fromSystemProperties();
  private final Map<Variant, PackPipeline> geometry = new HashMap<>();
  private final Map<String, PackPipeline> screens = new HashMap<>();
  private final Map<PackPipeline, PackPipeline> sparseScreens = new IdentityHashMap<>();
  private long sparseProjectionPasses, sparseProjectionFallbacks;
  private final Map<PackPipeline, FullscreenOverwrite.Result> screenOverwrites =
      new IdentityHashMap<>();
  private final Map<UniformKey, GpuBufferSlice> frameBuffers = new HashMap<>();
  private PackPrograms programs;
  private PackRenderTargets targets;
  private PackTextures textures;
  private MaterialAtlas materialAtlas;
  private GpuTexture blockAtlas;
  private PackMipmaps mipmaps;
  private UnusedMipmaps unusedMipmaps;
  private long mipmapGenerations;
  private PackDepthMerge depthMerge;
  private String dimension;
  private ShaderRenderPass openPass;
  private boolean active, hand, shadow, raw, deferred;
  private long setupProgramsMs;

  public ShaderRuntime(ShaderPack pack, Map<String, String> options, ShaderProperties properties)
      throws IOException {
    this.pack = pack;
    this.options = Map.copyOf(options);
    this.properties = properties;
    String materialPath = properties.get("materialPalette", "");
    materialPalette =
        materialPath.isEmpty() ? null : MaterialPalette.parse(pack.text(materialPath));
    warmUp();
  }

  /**
   * Cadence: the pack's programs for the dimension the player is in (the overworld at the title screen) start translating
   * on background threads as soon as the pack is chosen, so the first frame with the pack finds them ready (from the
   * translation cache, or translated meanwhile) instead of doing that work itself. -Dcadence.shaders.warmUp=false: off.
   */
  private java.util.concurrent.CompletableFuture<PackPrograms> warm;
  private String warmDimension;
  private ShaderCompatibilityCompiler.ShadowComparison warmComparison;
  private volatile Map<String, PackTextures.Decoded> warmImages = Map.of();

  /**
   * Cadence: waits (off the render thread) until the warm-up is done, so a pack switched to in a running game is installed
   * with its programs and images ready and its first frame doesn't pause the game for them. False if it failed or there
   * is none (the first frame then does that work, and reports a failure, as before).
   */
  public boolean awaitWarm() {
    var future = warm;
    if (future == null) return false;
    try {
      future.join();
      return true;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private void warmUp() {
    if ("false".equals(System.getProperty("cadence.shaders.warmUp"))) return;
    try {
      var level = Minecraft.getInstance().level;
      String dimension = level == null ? "minecraft:overworld" : level.dimension().identifier().toString();
      var comparison = PackTextures.shadowComparison(device());
      warmDimension = dimension;
      warmComparison = comparison;
      warm = new java.util.concurrent.CompletableFuture<>();
      var future = warm;
      Thread.ofPlatform().daemon().name("cadence-pack-warm-up").start(() -> {
        try {
          PackPrograms made = new PackPrograms(pack, options, dimension, comparison);
          warmImages = PackTextures.decode(pack, made.properties);
          // the game pipelines packs drew with (this session, and earlier ones: PipelineMemory), translated for this pack now
          // (prewarmPipelines compiles them)
          PipelineMemory.load(SESSION_VARIANTS);
          java.util.List<java.util.function.Consumer<ShaderCompatibilityCompiler>> tasks = new java.util.ArrayList<>();
          for (Variant v : SESSION_VARIANTS) tasks.add(with -> adapt(made, with, v)); // (one that fails: left for its first use)
          made.withCompilers(tasks);
          future.complete(made);
        } catch (Throwable failure) {
          future.completeExceptionally(failure);
        }
      });
    } catch (RuntimeException e) {
      warm = null; // no device yet (very early): the first frame translates as before
    }
  }

  /**
   * Cadence: once a pack runs, the other dimensions' programs are translated too, slowly (2 low-priority workers, after
   * 20 s), into the translation cache: the first trip through a portal found them untranslated and froze the game for
   * their translation (Complementary's End: 13 s, Bliss's Nether: 6 s).
   */
  private volatile boolean otherDimensionsWarmed, closedRuntime;

  private void warmOtherDimensions() {
    if (otherDimensionsWarmed || "false".equals(System.getProperty("cadence.shaders.warmUp"))) return;
    otherDimensionsWarmed = true;
    String current = dimension;
    ShaderCompatibilityCompiler.ShadowComparison comparison;
    try {
      comparison = PackTextures.shadowComparison(device());
    } catch (RuntimeException e) {
      return;
    }
    Thread.ofPlatform().daemon().priority(Thread.MIN_PRIORITY).name("cadence-pack-warm-dimensions").start(() -> {
      try {
        Thread.sleep(20_000);
      } catch (InterruptedException e) {
        return;
      }
      PackPrograms.BACKGROUND.set(Boolean.TRUE);
      for (String d : java.util.List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
        if (d.equals(current) || closedRuntime) continue;
        long start = System.nanoTime();
        try (PackPrograms p = new PackPrograms(pack, options, d, comparison)) {
          LoggerFactory.getLogger("cadence_shaders")
              .info("Pack {} programs for {} translated in the background ({} ms)", pack.name(), d, (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable failure) {
          // that dimension's own load reports it
        }
      }
    });
  }

  /** The warmed-up programs if they are for this dimension and comparison mode (waiting for them if still running). */
  private PackPrograms takeWarm(String dimension, ShaderCompatibilityCompiler.ShadowComparison comparison) {
    var future = warm;
    warm = null;
    if (future == null) return null;
    if (!dimension.equals(warmDimension) || comparison != warmComparison) {
      future.thenAccept(PackPrograms::close);
      return null;
    }
    try {
      return future.join();
    } catch (RuntimeException failure) { // the frame's own translation reports it, as before
      return null;
    }
  }

  public static void install(ShaderRuntime runtime) {
    // (Cadence API: dev.cadence.api.Cadence#shaderPack)
    dev.cadence.metal.ApiHooks.shaderPack = runtime == null ? "" : dev.cadence.shaders.cadence.ui.ShaderToggle.selectedPack();
    dev.cadence.metal.ApiHooks.shadowPass = false;
    if (runtime == null) dev.cadence.metal.ApiHooks.sunPathRotation = 0;
    instance = runtime;
    // (Cadence: lazy clears are on with and without a pack now: MetalEncoder.LAZY_CLEARS)
  }

  public static ShaderRuntime get() {
    return instance;
  }

  /** Cadence: a render flag of the active pack's shaders.properties (clouds, sun, moon, stars, ...), or null. */
  public String renderFlagValue(String name) {
    PackPrograms p = programs;
    return p == null ? null : p.properties.values().get(name);
  }

  public boolean renderFlag(String name, boolean fallback) {
    String v = renderFlagValue(name);
    return v == null ? fallback : !(v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off") || v.equals("0"));
  }

  public static boolean isRendering() {
    return instance != null && instance.active;
  }

  public GpuDevice device() {
    return RenderSystem.getDevice();
  }

  public FrameUniforms uniforms() {
    return uniforms;
  }

  public PackPrograms programs() {
    return programs;
  }

  /**
   * Cadence: the far terrain with this pack, for the Far Terrain page and Video Settings: "draws" (its dh_terrain program
   * draws it), "none" (no Distant Horizons support: no far terrain with it), "failed" (its dh_terrain program didn't
   * translate or compile), "" (no far terrain in this dimension, or none running).
   */
  public String distantTerrainState() {
    PackPrograms p = programs;
    if (p == null || !p.distantProgram) return p != null && dev.cadence.shaders.cadence.render.DistantTerrain.available() ? "none" : "";
    if (!p.distantTerrain) return "";
    return distantFailed || p.find("dh_terrain") == null ? "failed" : "draws";
  }

  public ShaderProperties properties() {
    return properties;
  }

  public PackRenderTargets targets() {
    return targets;
  }

  public MaterialAtlas.Stats materialStats() {
    return materialAtlas == null ? new MaterialAtlas.Stats(0, 0, 0, 0, 0) : materialAtlas.stats();
  }

  public void beginFrame() {
    ShadowRenderer.beginFrame();
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.level == null) return;
    // Refresh after resource reloads; a held block and terrain can share this same atlas.
    blockAtlas =
        minecraft
            .getTextureManager()
            .getTexture(TextureAtlas.LOCATION_BLOCKS)
            .getTextureView()
            .texture();
    var main = minecraft.gameRenderer.mainRenderTarget();
    var chunks = minecraft.levelRenderer.sectionRenderDispatcher();
    dev.cadence.shaders.cadence.render.RenderScale.frame(
        System.nanoTime(), chunks != null && chunks.getCompileQueueSize() > 32);
    float scale = dev.cadence.shaders.cadence.render.RenderScale.get();
    frameWidth = Math.max(1, Math.round(main.width * scale));
    frameHeight = Math.max(1, Math.round(main.height * scale));
    String nextDimension = minecraft.level.dimension().identifier().toString();
    try {
      if (programs == null || !nextDimension.equals(dimension)) {
        disposeGraph();
        var shadowComparison = PackTextures.shadowComparison(device());
        long programsStart = System.nanoTime();
        if (storage != null) storage.close();
        storage = null;
        setupDone = false;
        PackPrograms warmed = takeWarm(nextDimension, shadowComparison);
        programs = warmed != null ? warmed : new PackPrograms(pack, options, nextDimension, shadowComparison);
        PackBlend.configure(programs.properties.values()); // before any of its pipelines is made
        setupProgramsMs = (System.nanoTime() - programsStart) / 1_000_000;
        unusedMipmaps =
            Boolean.getBoolean("cadence.shaders.skipUnusedMipmaps")
                ? UnusedMipmaps.analyze(programs)
                : null;
        if (unusedMipmaps != null)
          LoggerFactory.getLogger("cadence_shaders")
              .info("Proven unused mip generations: {}", unusedMipmaps.skipped());
        uniforms.setCustomUniforms(CustomUniforms.compile(programs.properties));
        textures = new PackTextures(device(), pack, programs.properties, shadowComparison,
            warmed != null ? warmImages : Map.of());
        warmImages = Map.of();
        {
          // which colour buffers each screen program finds already drawn this frame (PackTextures.setWrittenBefore)
          Map<String, java.util.Set<Integer>> writtenBefore = new java.util.HashMap<>();
          java.util.Set<Integer> written = new java.util.HashSet<>();
          for (String phase : new String[] {"setup", "begin", "prepare", "deferred", "composite"})
            for (var step : programs.steps(phase)) {
              writtenBefore.putIfAbsent(step.source().name(), java.util.Set.copyOf(written));
              if (!step.compute())
                for (int index : step.source().translated().drawBuffers()) if (index >= 0) written.add(index);
            }
          PackPrograms.Source fin = programs.find("final");
          if (fin != null) writtenBefore.put(fin.name(), java.util.Set.copyOf(written));
          textures.setWrittenBefore(writtenBefore);
          textures.setSamplerTypes(programs.samplerTypes());
        }
        mipmaps = new PackMipmaps(device());
        depthMerge = new PackDepthMerge(device());
        dimension = nextDimension;
        warmOtherDimensions();
      }
      if (programs.readsSampler("materialtex")) {
        if (materialAtlas == null) {
          if (materialPalette == null)
            throw new IOException("materialtex requires a materialPalette declaration");
          materialAtlas = new MaterialAtlas(device(), materialPalette);
        }
        materialAtlas.refresh(
            (TextureAtlas) minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS),
            minecraft.getResourceManager());
      }
      if (targets == null || targets.width != frameWidth || targets.height != frameHeight) {
        // Pipeline state depends on attachment formats, not the size of their textures.
        frameBuffers.clear();
        // Cadence: the old targets live until their history is carried over (below)
        PackRenderTargets old = targets;
        targets = null;
        Map<Integer, PackRenderTargets.BufferSpec> specs = programs.bufferSpecifications();
        targets =
            new PackRenderTargets(
                device(),
                specs,
                frameWidth,
                frameHeight,
                programs.globals.intConstant("shadowMapResolution", 1024));
        targets.setShadowSpecifications(programs.shadowSpecifications());
        targets.setStorageImages(programs.storageImages(false), programs.storageImages(true));
        // Cadence: shadow color targets nothing samples are left unattached in the shadow pass.
        var packPrograms = programs;
        targets.setShadowColorRead(
            index ->
                packPrograms.readsSampler("shadowcolor" + index)
                    || index == 0 && packPrograms.readsSampler("shadowcolor"));
        LoggerFactory.getLogger("cadence_shaders")
            .info(
                "Shadow color targets sampled: 0={} 1={}",
                targets.shadowColorUsed(0),
                targets.shadowColorUsed(1));
        // Allocate before any native scene pass opens, never in the middle of a draw batch.
        for (int index : specs.keySet()) targets.color(index);
        targets.shadowColor(0);
        targets.shadowColor(1);
        // Cadence: a resize (Auto resolution moves every few seconds) keeps the pack's history: the buffers it never
        // clears (TAA, temporal reflections and shadows) are carried over, rescaled. Remade empty, every TAA pack showed
        // its raw noise (dots, shimmer) for the frames it took to converge again, each time the resolution moved.
        if (old != null) {
          int carried = 0;
          if (mipmaps != null && !"false".equals(System.getProperty("cadence.shaders.carryHistory")))
            for (int index : old.historyBuffers()) {
              if (!specs.containsKey(index)) continue;
              PackTexture from = old.color(index);
              old.ensureDefined(from);
              if (mipmaps.blit(from, targets.color(index))) {
                targets.ensureDefined(targets.color(index)); // (drops its pending clear: the blit wrote all of it)
                carried++;
              }
            }
          old.close();
          if (carried > 0)
            LoggerFactory.getLogger("cadence_shaders").debug("Carried {} history buffers over a resize", carried);
        }
        long screensStart = System.nanoTime();
        prewarmPipelines();
        for (String phase : new String[] {"setup", "begin", "prepare", "deferred", "composite", "shadowcomp"}) {
          for (var step : programs.steps(phase)) {
            if (step.compute()) kernel(step.source());
            else screenPipeline(step.source());
          }
        }
        if (programs.find("final") != null) screenPipeline(programs.find("final"));
        LoggerFactory.getLogger("cadence_shaders")
            .info(
                "Pack {} initialized in {} at {}x{}; shadow comparisons: {}; programs {} ms, screen pipelines {} ms"
                    + " (translation cache {} hits, {} from disk, {} translated; SPIR-V cache {} hits, {} compiled)",
                pack.name(),
                nextDimension,
                frameWidth,
                frameHeight,
                textures.hardwareShadowComparison() ? "hardware" : "emulated",
                setupProgramsMs,
                targets == null ? 0 : (System.nanoTime() - screensStart) / 1_000_000,
                dev.cadence.shaders.compile.TranslationCache.hits,
                dev.cadence.shaders.compile.TranslationCache.diskHits,
                dev.cadence.shaders.compile.TranslationCache.misses,
                dev.cadence.shaders.compile.SpirvCache.hits,
                dev.cadence.shaders.compile.SpirvCache.misses);
      }
      prepareScaledOutput(main);
      PipelineMemory.maybeSave(SESSION_VARIANTS, false);
      if (profiler != null) profiler.beginFrame(device(), pack.name() + " " + dimension, frameWidth, frameHeight);
      frameBuffers.clear();
      dev.cadence.shaders.cadence.packcheck.PackCheck.beforeFrame(this);
      uniforms.beginFrame(minecraft, frameWidth, frameHeight, programs.globals);
      if (uniforms.historyReset())
        targets.resetHistory(
            minecraft.gameRenderer.gameRenderState()
                .levelRenderState
                .cameraRenderState
                .fogData
                .color);
      // Cadence: every Nth frame draws the shadow map; the others keep it (ShadowReuse).
      var cameraPos =
          minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
      // Cadence: a shadow pass that writes images or buffer objects (voxelization, Pegasus) runs every frame
      boolean reuseShadow =
          programs.find("shadow") != null
              && !shadowWritesStorage()
              && dev.cadence.shaders.cadence.shadow.ShadowReuse.decide(
                  targets, cameraPos, uniforms.shadowDirectionWorld());
      targets.beginFrame(
          minecraft.gameRenderer.gameRenderState()
              .levelRenderState
              .cameraRenderState
              .fogData
              .color,
          !reuseShadow);
      profile("targets.cleared");
      if (!reuseShadow) shadowMipsDone.clear(); // a reused shadow map keeps its mipmaps
      geometryMipsDone.clear();
      if (reuseShadow) {
        uniforms.setShadowMatrices(
            dev.cadence.shaders.cadence.shadow.ShadowReuse.projection(),
            dev.cadence.shaders.cadence.shadow.ShadowReuse.view(cameraPos));
      } else {
        updateShadowMatrices();
        dev.cadence.shaders.cadence.shadow.ShadowReuse.drawn(
            targets,
            cameraPos,
            uniforms.shadowDirectionWorld(),
            uniforms.shadowProjection(),
            uniforms.shadowModelView());
      }
      active = true;
      hand = false;
      shadow = false;
      dev.cadence.metal.ApiHooks.shadowPass = false;  // (the API flag with it, BH5-10)
      deferred = false;
      dev.cadence.shaders.cadence.render.DistantTerrain.want(programs.distantTerrain && !distantFailed);
      // Cadence: the pack's images and buffer objects (sized for this frame, cleared where asked), its setup programs once
      if (storage == null) storage = new PackStorage(programs.properties);
      storage.beginFrame(frameWidth, frameHeight);
      if (!setupDone) {
        setupDone = true;
        execute("setup");
      }
      execute("begin");
      PackPrograms.Source sky = programs.find("gbuffers_skybasic");
      if (sky != null)
        execute(
            screens.computeIfAbsent(
                "sky_background",
                ignored ->
                    PackPipeline.compile(
                        device(),
                        sky,
                        programs.geometry(sky, MinecraftVertexAdapter.skyBackground()),
                        null,
                        targets,
                        false,
                        false,
                        0,
                        PackEnvironment.stage("SKY"),
                        0)));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot initialize shader pack " + pack.name(), failure);
    }
  }

  private void updateShadowMatrices() throws IOException {
    ShadowRenderer.updateMatrices(this);
  }

  public boolean isShadow() {
    return shadow;
  }

  public boolean beginShadow() {
    if (!active || programs.find("shadow") == null) return false;
    suspend();
    profile("shadow.begin");
    shadow = true;
    dev.cadence.metal.ApiHooks.shadowPass = true;
    frameBuffers.clear();
    return true;
  }

  /** Cadence: a frame that keeps the last shadow map (ShadowReuse) runs what follows the shadow pass. */
  public void skipShadow() {
    if (!active || programs.find("shadow") == null) return;
    suspend();
    frameBuffers.clear();
    execute("prepare");
  }

  public void endShadow() {
    dev.cadence.metal.ApiHooks.shadowPass = false; // (first: suspend() can throw, BH5-10)
    suspend();
    profile("shadow.end");
    shadow = false;
    frameBuffers.clear();
    execute("shadowcomp");
    execute("prepare");
  }

  public void setProjection(Matrix4fc projection) {
    if (!active) return;
    uniforms.setProjection(projection);
    frameBuffers.clear();
  }

  // ---- Cadence: the far terrain through the pack's dh_terrain program (DistantTerrain) ----

  private PackPipeline distantPipeline;
  /** The pack's dh_terrain program didn't compile: the far terrain isn't drawn with this pack (the pack runs on). */
  private boolean distantFailed;
  private static boolean distantLogged;

  /**
   * Start of the level's solid pass (the sky drawn, the real terrain not yet): the far terrain, culled this frame, drawn by
   * the pack's dh_terrain program into its colour buffers with dhDepthTex0; the real terrain then draws over it.
   */
  public void drawDistantTerrain() {
    if (!active || shadow || deferred || !programs.distantTerrain || distantFailed) return;
    if (!dev.cadence.shaders.cadence.render.DistantTerrain.prepare()) return;
    GpuBuffer arguments = dev.cadence.shaders.cadence.render.DistantTerrain.drawArguments();
    if (arguments == null) return;
    PackPipeline pipeline = distantPipeline;
    if (pipeline == null) {
      PackPrograms.Source source = programs.find("dh_terrain");
      try {
        if (source == null) throw new IllegalStateException("no dh_terrain program");
        pipeline = distantPipeline =
            PackPipeline.compile(
                device(), source, source.translated(), dev.cadence.shaders.cadence.render.DistantTerrain.pipeline(), targets,
                false, false, PackRenderTargets.DH_DEPTH, PackEnvironment.STAGES.indexOf("TERRAIN_SOLID"), 0f);
      } catch (RuntimeException failure) {
        distantFailed = true;
        dev.cadence.shaders.cadence.render.DistantTerrain.want(false);
        LoggerFactory.getLogger("cadence_shaders").warn("Far terrain not drawn with this pack: its dh_terrain program failed", failure);
        return;
      }
    }
    if (!distantLogged) {
      distantLogged = true;
      LoggerFactory.getLogger("cadence_shaders").info("Far terrain drawn by the pack's dh_terrain program");
    }
    suspend();
    profile("far_terrain.begin");
    prepare(pipeline);
    try (RenderPass pass = open(pipeline)) {
      pass.setPipeline(pipeline.compiled());
      bind(pipeline, pass, Map.of());
      pass.setIndexBuffer(dev.cadence.shaders.cadence.render.DistantTerrain.indices(), com.mojang.renderpearl.api.pipeline.IndexType.SHORT);
      pass.drawIndexedIndirect(arguments.slice(0, RenderPass.INDIRECT_INDEXED_DRAW_SIZE), 1);
    }
    profile("far_terrain.end");
  }

  public void beforeTranslucents() {
    if (!active || shadow || deferred) return;
    suspend();
    profile("world.opaque.end");
    // depthtex1 also receives opaque hand rendering; it must start from scene depth.
    targets.copyDepth(1);
    // (the far terrain's depth as the translucent phase reads it: dhDepthTex1, only for packs that read it)
    if (programs.distantTerrain && programs.readsSampler("dhDepthTex1")) targets.copyDistantDepth();
    if (programs.readsSampler("depthtex2")) targets.copyDepth(2);
    profile("depth.snapshot.end");
    execute("deferred");
    profile("deferred.end");
    targets.invalidateFeedback(); // translucent programs read what the opaque world and deferred passes made
    deferred = true;
  }

  public void beginHand() {
    if (!active) return;
    beforeTranslucents();
    suspend();
    profile("hand.begin");
    hand = true;
  }

  public void endFrame() {
    ShadowRenderer.discardSelection();
    if (!active) return;
    try {
      suspend();
      beforeTranslucents();
      if (hand) {
        profile("hand.end");
        if (programs.postReadsSampler("depthtex0")) depthMerge.merge(targets);
        profile("depth.hand_merge.end");
      }
      execute("composite");
      PackPrograms.Source finalProgram = programs.find("final");
      if (finalProgram == null)
        throw new UnsupportedOperationException("Pack requires a final output program");
      execute(screenPipeline(finalProgram));
      // Cadence (PackCheck): the pack's picture, before the upscale and the GUI
      dev.cadence.shaders.cadence.packcheck.PackCheck.afterFinal(
          scaledOutput != null ? scaledOutput : Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture());
      if (scaledOutputView != null) {
        profile("upscale.begin");
        // Our own pass: raw, so intercept() doesn't take it for a scene pass of the game's.
        raw = true;
        try {
          var mainView = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
          // Cadence: the Neural Engine upscaler when chosen (upscaler=neural; MetalFX until it's ready, or if it can't), Apple's
          // MetalFX spatial upscaler when chosen (upscaler=metalfx), else (or if it can't) the shader upscale.
          if (!dev.cadence.shaders.cadence.render.RenderScale.neural()) dev.cadence.metal.NeuralUpscaler.release();
          boolean neural = dev.cadence.shaders.cadence.render.RenderScale.neural()
              && dev.cadence.metal.NeuralUpscaler.encode(scaledOutputView, mainView);
          if (!neural && (!dev.cadence.shaders.cadence.render.RenderScale.metalFx()
              || !dev.cadence.metal.MetalFxUpscaler.encode(scaledOutputView, mainView)))
          {
            if (upscale.fast() != dev.cadence.shaders.cadence.render.RenderScale.fastUpscale()) {
              float sharpen = dev.cadence.shaders.cadence.render.RenderScale.sharpenFor(dev.cadence.shaders.cadence.render.RenderScale.get());
              upscale.close();
              upscale = new PackUpscale(device(), scaledOutput.getFormat(), sharpen, dev.cadence.shaders.cadence.render.RenderScale.fastUpscale());
            }
            upscale.run(device(), scaledOutputView, mainView);
          }
        } finally {
          raw = false;
        }
        profile("upscale.end");
      }
      uniforms.endFrame();
      if (profiler != null) profiler.endFrame();
    } finally {
      if (profiler != null) profiler.abortFrame();
      active = false;
      hand = false;
      shadow = false;
      dev.cadence.metal.ApiHooks.shadowPass = false;  // (the API flag with it, BH5-10)
      OriginalPipelines.prune();
    }
  }

  public void abortFrame() {
    ShadowRenderer.discardSelection();
    suspend();
    if (profiler != null) profiler.abortFrame();
    active = false;
    hand = false;
    shadow = false;
    dev.cadence.metal.ApiHooks.shadowPass = false;  // (the API flag with it, BH5-10)
  }

  public boolean intercept(RenderPassDescriptor descriptor) {
    if (!active || raw || descriptor.colorAttachments().isEmpty()) return false;
    var first = descriptor.colorAttachments().getFirst();
    return first != null
        && first.textureView().texture()
            == Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
  }

  public void opened(ShaderRenderPass pass) {
    if (openPass != null) throw new IllegalStateException("Overlapping logical scene passes");
    openPass = pass;
  }

  public void closed(ShaderRenderPass pass) {
    if (openPass == pass) openPass = null;
  }

  public void suspend() {
    if (openPass != null) openPass.suspend();
  }

  public PackPipeline pipeline(RenderPipeline original) {
    boolean blockEntity =
        FeatureDrawContext.currentDrawTag().kind() == FeatureDrawContext.Kind.BLOCK_ENTITY;
    Variant key = new Variant(original, hand, shadow, blockEntity);
    PackPipeline made = geometry.get(key);
    if (made == null) {
      made = compileVariant(key);
      geometry.put(key, made);
    }
    return made;
  }

  /** Cadence: the pack pipeline for one of the game's pipelines (any thread: see prewarmPipelines). */
  private PackPipeline compileVariant(Variant variant) {
    if (SESSION_VARIANTS.size() < 512 && SESSION_VARIANTS.add(variant)) PipelineMemory.changed();
    Adapted adapted = adapt(programs, variant);
    boolean hand = variant.hand();
    int depthTarget = hand && !adapted.selection().name().equals("gbuffers_hand_water") ? 1 : 0;
    return PackPipeline.compile(
        device(),
        adapted.source(),
        adapted.translated(),
        variant.original(),
        targets,
        false,
        variant.shadow(),
        depthTarget,
        adapted.selection().stage(),
        adapted.alphaTest().reference());
  }

  private record Adapted(
      SceneProgram selection,
      PackPrograms.Source source,
      dev.cadence.shaders.compile.TranslatedProgram translated,
      AlphaTestPolicy alphaTest) {}

  /**
   * Cadence: which pack program draws one of the game's pipelines, translated for its vertex layout and alpha test. Needs
   * only the programs, so the warm-up thread runs it for the pipelines this session has seen (the translations then come
   * from the cache when the pipelines are compiled).
   */
  private static Adapted adapt(PackPrograms programs, Variant variant) {
    return adapt(programs, null, variant);
  }

  /** (with: another compiler set up as the pack's, from PackPrograms.withCompilers; null: the pack's own) */
  private static Adapted adapt(PackPrograms programs, ShaderCompatibilityCompiler with, Variant variant) {
    RenderPipeline original = variant.original();
    boolean hand = variant.hand(), shadow = variant.shadow();
          SceneProgram selection = SceneProgram.select(original, hand, shadow, variant.blockEntity());
          var source = programs.resolve(selection.name());
          // -Dcadence.shaders.logPipelines=true: which pack program each of the game's pipelines draws with
          if (Boolean.getBoolean("cadence.shaders.logPipelines"))
            LoggerFactory.getLogger("cadence_shaders").info("[pipelines] {}{}{} -> {} defines={} depth={} blend={}", original.getLocation(), shadow ? " (shadow)" : "", hand ? " (hand)" : "", source.name(), original.getShaderDefines().values(), original.getDepthStencilState(), original.getColorTargetStates());
          boolean cutout = original.getShaderDefines().values().containsKey("ALPHA_CUTOUT");
          float alpha = cutout ? Float.parseFloat(original.getShaderDefines().values().get("ALPHA_CUTOUT")) : 0.1f;
          // Cadence: as Iris, an opaque draw the game doesn't alpha-test (solid terrain, solid entities) isn't tested
          // unless the pack asks (alphaTest.<program>). The test's discard kept Apple GPUs from culling hidden fragments
          // before shading them: every pack's terrain paid for its overdraw.
          boolean blended =
              !original.getColorTargetStates().isEmpty()
                  && original.getColorTargetStates().getFirst() != null
                  && original.getColorTargetStates().getFirst().blendFunction().isPresent();
          AlphaTestPolicy defaultTest =
              cutout || blended || !OPAQUE_UNTESTED ? AlphaTestPolicy.greater(alpha) : AlphaTestPolicy.OFF;
          dev.cadence.shaders.compile.TranslatedProgram translated;
          AlphaTestPolicy alphaTest;
          try {
            boolean waterMask = MinecraftVertexAdapter.isWaterMask(original);
            alphaTest =
                waterMask
                    ? AlphaTestPolicy.OFF
                    : AlphaTestPolicy.parse(
                        programs.properties.get("alphaTest." + source.name(), ""),
                        defaultTest);
            translated =
                with == null
                    ? programs.geometry(source, MinecraftVertexAdapter.adapter(original, shadow, hand), alphaTest)
                    : programs.geometry(with, source, MinecraftVertexAdapter.adapter(original, shadow, hand), alphaTest);
            if (waterMask) translated = MinecraftVertexAdapter.waterMaskProgram(translated);
            translated =
                dev.cadence.shaders.compile.MinecraftTextureAdapter.adapt(original, translated);
          } catch (IOException failure) {
            throw new UncheckedIOException("Invalid alpha test for " + source.name(), failure);
          } catch (RuntimeException failure) {
            throw new IllegalStateException(
                "Cannot adapt Minecraft pipeline "
                    + original.getLocation()
                    + " to pack program "
                    + source.name(),
                failure);
          }
    return new Adapted(selection, source, translated, alphaTest);
  }

  private PackPipeline screenPipeline(PackPrograms.Source source) {
    PackPipeline made = screens.get(source.name());
    if (made == null) {
      PackPipeline[] pair = compileScreen(source);
      made = pair[0];
      screens.put(source.name(), made);
      if (pair[1] != null) sparseScreens.put(made, pair[1]);
    }
    return made;
  }

  /** Cadence: a screen program's pipeline and its sparse-projection variant (or null); any thread. */
  private PackPipeline[] compileScreen(PackPrograms.Source source) {
    String name = source.name();
    // Cadence: a shadow composite pass (shadowcomp) draws over the shadow map, into the shadow colour buffers (Nostalgia,
    // Kappa and Vanilla Plus build their water caustics there); every other screen pass into the colour buffers
    boolean shadowComposite = name.matches("shadowcomp[0-9]*");
    if (shadowComposite) {
      return new PackPipeline[] {
          PackPipeline.compile(device(), source, source.translated(), null, targets, false, true, 0, 0, 0), null};
    }
    PackPipeline dense =
        PackPipeline.compile(device(), source, source.translated(), null, targets, name.equals("final"), false, 0, 0, 0);
    PackPipeline sparse = null;
    if (SPARSE_PROJECTION) {
      var candidate =
          MatrixUniformSpecialization.apply(
              source.translated(), MatrixUniformSpecialization.PROJECTION_INVERSE);
      if (candidate.applied()) {
        try {
          sparse =
              PackPipeline.compile(
                  device(), source, candidate.program(), null, targets, name.equals("final"), false, 0, 0, 0);
        } catch (RuntimeException failure) {
          LoggerFactory.getLogger("cadence_shaders")
              .warn("Sparse projection variant {} unavailable; retaining dense shader", name, failure);
        }
      }
    }
    return new PackPipeline[] {dense, sparse};
  }

  /**
   * Cadence: the game pipelines (with their hand, shadow and block-entity flags) a pack has drawn with in this session. A
   * pack loaded later (another pack, or the same one with changed settings) has its versions of them compiled up front, in
   * parallel (prewarmPipelines), instead of one after another as its first frames meet them.
   */
  private static final java.util.Set<Variant> SESSION_VARIANTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** -Dcadence.shaders.compileThreads=N: threads compiling a pack's pipelines at once (1: one at a time, as before). */
  private static final int COMPILE_THREADS =
      Integer.getInteger("cadence.shaders.compileThreads", Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));

  /**
   * Cadence: every screen program's pipeline and the game pipelines this session has seen (SESSION_VARIANTS), compiled on
   * COMPILE_THREADS threads at once; the render thread waits for all of them. Metal compiles each library in its own
   * service, so they overlap: a settings change the shader caches hadn't seen paused the game 1.7-2.1 s for ~30 pipelines
   * built one by one. Anything that fails here is left for its first use, which reports it as before.
   */
  private void prewarmPipelines() {
    PipelineMemory.load(SESSION_VARIANTS);
    java.util.Map<String, PackPrograms.Source> screenSources = new java.util.LinkedHashMap<>();
    for (String phase : new String[] {"setup", "begin", "prepare", "deferred", "composite", "shadowcomp"})
      for (var step : programs.steps(phase))
        if (!step.compute() && !screens.containsKey(step.source().name())) screenSources.putIfAbsent(step.source().name(), step.source());
    PackPrograms.Source fin = programs.find("final");
    if (fin != null && !screens.containsKey(fin.name())) screenSources.putIfAbsent(fin.name(), fin);
    java.util.List<Variant> variants = new java.util.ArrayList<>();
    for (Variant v : SESSION_VARIANTS) if (!geometry.containsKey(v)) variants.add(v);
    if (COMPILE_THREADS <= 1 || screenSources.size() + variants.size() < 2) return;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(
        COMPILE_THREADS, Thread.ofPlatform().daemon().name("cadence-pipeline-compile-", 0).factory());
    try {
      Map<String, java.util.concurrent.Future<PackPipeline[]>> screenJobs = new java.util.LinkedHashMap<>();
      Map<Variant, java.util.concurrent.Future<PackPipeline>> variantJobs = new java.util.LinkedHashMap<>();
      // geometry first: there are more of them and they're the slower ones
      for (Variant v : variants) variantJobs.put(v, pool.submit(() -> compileVariant(v)));
      for (var e : screenSources.entrySet()) screenJobs.put(e.getKey(), pool.submit(() -> compileScreen(e.getValue())));
      for (var e : variantJobs.entrySet()) {
        PackPipeline made = joinQuietly(e.getValue());
        if (made != null) geometry.putIfAbsent(e.getKey(), made);
      }
      for (var e : screenJobs.entrySet()) {
        PackPipeline[] pair = joinQuietly(e.getValue());
        if (pair != null && screens.putIfAbsent(e.getKey(), pair[0]) == null && pair[1] != null)
          sparseScreens.put(pair[0], pair[1]);
      }
    } finally {
      pool.shutdown();
    }
  }

  private static <T> T joinQuietly(java.util.concurrent.Future<T> future) {
    try {
      return future.get();
    } catch (java.util.concurrent.ExecutionException failure) {
      LoggerFactory.getLogger("cadence_shaders").debug("Pipeline compiled ahead failed; left for its first use", failure.getCause());
      return null;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    }
  }


  public long sparseProjectionPasses() {
    return sparseProjectionPasses;
  }

  public long sparseProjectionFallbacks() {
    return sparseProjectionFallbacks;
  }

  public long mipmapGenerations() {
    return mipmapGenerations;
  }

  // Cadence: a pack program's mipmapped buffers, once per program (the directives scan runs a regex over every constant,
  // and was asked on every pipeline change and pass open). Render thread.
  private final java.util.IdentityHashMap<dev.cadence.shaders.pack.ShaderDirectives, java.util.Set<Integer>> mipmapBuffers =
      new java.util.IdentityHashMap<>();

  private java.util.Set<Integer> mipmapBuffers(PackPipeline pipeline) throws IOException {
    var directives = pipeline.source().directives();
    var known = mipmapBuffers.get(directives);
    if (known == null) {
      known = directives.mipmapBuffers();
      mipmapBuffers.put(directives, known);
    }
    return known;
  }

  /** Cadence: shadow color buffers whose mipmaps were made since this frame's shadow pass. */
  private final java.util.Set<Integer> shadowMipsDone = new java.util.HashSet<>();
  /**
   * Cadence: color buffers whose mipmaps a world-geometry program got this frame. Iris makes mipmaps only before the screen
   * passes that ask for them; a geometry program gets them once a frame, and only for a buffer it reads (Pegasus's terrain
   * asked for colortex0's: its 9-level chain was made again before every terrain and entity batch).
   */
  private final java.util.Set<Integer> geometryMipsDone = new java.util.HashSet<>();

  private boolean geometryMipsWanted(PackPipeline pipeline, int index) {
    if (geometryMipsDone.contains(index)) return false;
    for (var sampler : pipeline.samplerBindings())
      if (sampler.kind() == PackPipeline.SamplerKind.COLOR && sampler.index() == index) return true;
    return false;
  }

  public void prepare(PackPipeline pipeline) {
    try {
      var requested = mipmapBuffers(pipeline);
      boolean generated = false;
      if (pipeline.fullscreen()) {
        // Cadence: shadowColorNMipmap: the mip chain is made once a frame, before the first screen pass that reads it
        for (var sampler : pipeline.samplerBindings()) {
          if (sampler.kind() != PackPipeline.SamplerKind.SHADOW_COLOR
              || !targets.shadowSpecification(sampler.index()).mipmaps()
              || !shadowMipsDone.add(sampler.index())) continue;
          PackTexture texture = targets.shadowColor(sampler.index());
          targets.ensureDefined(texture);
          if (texture.texture.getMipLevels() <= 1) continue;
          mipmaps.generate(texture);
          mipmapGenerations++;
          generated = true;
        }
      }
      for (int index : requested) {
        if (pipeline.fullscreen()
            && unusedMipmaps != null
            && unusedMipmaps.skips(pipeline.source().name(), index)) continue;
        if (!pipeline.fullscreen()) {
          if (!geometryMipsWanted(pipeline, index)) continue;
          geometryMipsDone.add(index);
        }
        PackTexture texture = targets.color(index);
        targets.ensureDefined(texture);
        if (texture.texture.getMipLevels() <= 1 || targets.mipsCurrent(texture)) continue;
        mipmaps.generate(texture);
        targets.markMipsCurrent(texture);
        mipmapGenerations++;
        generated = true;
      }
      if (generated) profile("mipmaps." + pipeline.source().name() + ".end");
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /** Cadence: this program samples color buffer index while drawing into it (see PackRenderTargets.colorFeedback). */
  private static boolean colorFeedback(PackPipeline pipeline, int index) {
    return !pipeline.fullscreen() && !pipeline.shadow() && pipeline.translated().drawBuffers().contains(index);
  }

  /** Cadence: this program samples depth buffer index, its own depth attachment. */
  private static boolean depthFeedback(PackPipeline pipeline, int index) {
    return !pipeline.fullscreen() && !pipeline.shadow() && pipeline.depth() && pipeline.depthTarget() == index;
  }

  /**
   * Cadence: a feedback copy for this sampler only when the program's code samples it (PackPipeline.reads): a declared,
   * unused sampler of a buffer the program draws into needs no copy (Complementary at 2940 x 1686: about 3 ms a frame of
   * copies). -Dcadence.shaders.feedbackReads=false: every declared one, as before.
   */
  public static volatile boolean FEEDBACK_READS = !"false".equals(System.getProperty("cadence.shaders.feedbackReads"));

  private static boolean copies(PackPipeline pipeline, PackPipeline.SamplerBinding sampler) {
    return !FEEDBACK_READS || pipeline.readsSampler(sampler);
  }

  /** Cadence: the feedback copies this program reads (made outside a pass: before it opens). */
  private void prepareFeedback(PackPipeline pipeline) {
    for (var sampler : pipeline.samplerBindings()) {
      if (textures.custom(pipeline.source().name(), sampler.name()) != null) continue;
      if (sampler.kind() == PackPipeline.SamplerKind.COLOR && colorFeedback(pipeline, sampler.index()) && copies(pipeline, sampler))
        targets.colorFeedback(sampler.index());
      else if (sampler.kind() == PackPipeline.SamplerKind.DEPTH && depthFeedback(pipeline, sampler.index()) && copies(pipeline, sampler))
        targets.depthFeedback(sampler.index());
      else if (sampler.kind() == PackPipeline.SamplerKind.SHADOW_COLOR && shadowColorFeedback(pipeline, sampler.index()))
        targets.copyShadowColorFeedback(sampler.index());
    }
  }

  /**
   * Cadence: a shadow composite pass reading a shadow colour buffer it also draws into (Nostalgia's caustics read
   * shadowcolor0 and write it): it reads a copy taken just before it, as Iris gives it the other side of a pair.
   */
  private static boolean shadowColorFeedback(PackPipeline pipeline, int index) {
    return pipeline.shadow() && pipeline.fullscreen() && pipeline.translated().drawBuffers().contains(index);
  }

  public boolean requiresReadPreparation(PackPipeline pipeline) {
    for (var sampler : pipeline.samplerBindings()) {
      if (textures.custom(pipeline.source().name(), sampler.name()) != null) continue;
      if (sampler.kind() == PackPipeline.SamplerKind.COLOR && colorFeedback(pipeline, sampler.index()) && copies(pipeline, sampler)
          && !targets.colorFeedbackReady(sampler.index())) return true;
      if (sampler.kind() == PackPipeline.SamplerKind.DEPTH && depthFeedback(pipeline, sampler.index()) && copies(pipeline, sampler)
          && !targets.depthFeedbackReady(sampler.index())) return true;
      if (sampler.kind() == PackPipeline.SamplerKind.SHADOW_COLOR && shadowColorFeedback(pipeline, sampler.index())) return true;
      boolean needed =
          switch (sampler.kind()) {
            case COLOR -> targets.needsColorDefinition(sampler.index());
            case DEPTH -> targets.needsDepthDefinition(sampler.index());
            case SHADOW_COLOR -> targets.needsShadowColorDefinition(sampler.index());
            case SHADOW_DEPTH -> targets.needsShadowDepthDefinition(sampler.index());
            default -> false;
          };
      if (needed) return true;
    }
    return false;
  }

  public boolean requiresMipGeneration(PackPipeline pipeline) {
    try {
      var requested = mipmapBuffers(pipeline);
      if (requested.isEmpty()) return false;
      if (pipeline.fullscreen()) return true;
      for (int index : requested) if (geometryMipsWanted(pipeline, index)) return true;
      return false;
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /** -Dcadence.shaders.flushSampledClears=false: a sampled texture's deferred clear is left to split the pass (A/B). */
  public static boolean FLUSH_SAMPLED_CLEARS = !"false".equals(System.getProperty("cadence.shaders.flushSampledClears"));

  public RenderPass open(PackPipeline pipeline) {
    return open(pipeline, 0);
  }

  private RenderPass open(PackPipeline pipeline, int discardMask) {
    // Reads must be defined before opening an encoder; a later binding cannot issue a clear.
    for (var sampler : pipeline.samplerBindings()) {
      if (textures.custom(pipeline.source().name(), sampler.name()) != null) continue;
      PackTexture target =
          switch (sampler.kind()) {
            case COLOR -> targets.color(sampler.index());
            case DEPTH -> targets.depth(sampler.index());
            case SHADOW_COLOR -> targets.shadowColor(sampler.index());
            case SHADOW_DEPTH -> targets.shadowDepth(sampler.index());
            default -> null;
          };
      if (target != null) {
        targets.ensureDefined(target);
        // Cadence: with lazy clears that clear is only noted; written now, before the pass opens, not by splitting it
        if (FLUSH_SAMPLED_CLEARS) dev.cadence.metal.MetalCompute.flushClear(target.texture);
      }
    }
    prepareFeedback(pipeline);
    RenderPassDescriptor descriptor =
        pipeline.source().name().equals("final")
            ? RenderPassDescriptor.builder(() -> "Shader pack final")
                .withColorAttachment(
                    scaledOutputView != null
                        ? scaledOutputView
                        : Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView())
                .build()
            : targets.descriptor(
                "Shader pack " + pipeline.source().name(),
                pipeline.translated().drawBuffers(),
                pipeline.fullscreen(),
                pipeline.shadow(),
                pipeline.depth(),
                pipeline.depthTarget());
    if (FLUSH_SAMPLED_CLEARS && !pipeline.fullscreen()) flushSceneSampledClears(descriptor, pipeline.shadow());
    raw = true;
    try {
      if (discardMask != 0) {
        try (var ignored = MetalPassHints.openDiscardColors(descriptor, discardMask)) {
          return device().createCommandEncoder().createRenderPass(descriptor);
        }
      }
      return device().createCommandEncoder().createRenderPass(descriptor);
    } finally {
      raw = false;
    }
  }

  /**
   * Cadence: the textures the pack's geometry programs sample (kind:index), over the pipelines compiled so far: [0] the
   * camera passes', [1] the shadow pass's.
   */
  @SuppressWarnings("unchecked")
  private final java.util.List<PackPipeline.SamplerBinding>[] sceneSamplers = new java.util.List[] {java.util.List.of(), java.util.List.of()};
  private int sceneSamplersFor = -1;

  /**
   * Cadence: a scene pass draws many programs in one native pass; a texture one of them samples that still has a deferred
   * clear split the pass at that draw (BSL's and Complementary's sun and moon: every frame). Those clears are written now,
   * before the pass opens; the pass's own attachments keep theirs (merged into the pass's load action).
   */
  private void flushSceneSampledClears(RenderPassDescriptor descriptor, boolean shadowPass) {
    if (sceneSamplersFor != geometry.size()) {
      for (int k = 0; k < 2; k++) {
        Map<String, PackPipeline.SamplerBinding> union = new java.util.LinkedHashMap<>();
        for (PackPipeline p : geometry.values())
          if (p.shadow() == (k == 1))
            for (var sampler : p.samplerBindings())
              if (textures.custom(p.source().name(), sampler.name()) == null)
                union.putIfAbsent(sampler.kind() + ":" + sampler.index(), sampler);
        sceneSamplers[k] = java.util.List.copyOf(union.values());
      }
      sceneSamplersFor = geometry.size();
    }
    var sampled = sceneSamplers[shadowPass ? 1 : 0];
    if (sampled.isEmpty()) return;
    java.util.Set<GpuTexture> attached = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    for (var attachment : descriptor.colorAttachments())
      if (attachment != null) attached.add(attachment.textureView().texture());
    if (descriptor.depthAttachment() != null) attached.add(descriptor.depthAttachment().textureView().texture());
    for (var sampler : sampled) {
      PackTexture target =
          switch (sampler.kind()) {
            case COLOR -> targets.color(sampler.index());
            case DEPTH -> targets.depth(sampler.index());
            case SHADOW_COLOR -> targets.shadowColor(sampler.index());
            case SHADOW_DEPTH -> targets.shadowDepth(sampler.index());
            default -> null;
          };
      if (target != null && !attached.contains(target.texture)) dev.cadence.metal.MetalCompute.flushClear(target.texture);
    }
  }

  public void bind(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied) {
    bindUniforms(pipeline, pass);
    bindTextures(pipeline, pass, supplied);
  }

  public void bindUniforms(PackPipeline pipeline, RenderPass pass) {
    if (pipeline.translated().uniforms().byteSize() > 0) {
      var tag =
          pipeline.fullscreen() ? FeatureDrawContext.NONE : FeatureDrawContext.currentDrawTag();
      GpuBufferSlice buffer =
          frameBuffers.computeIfAbsent(
              new UniformKey(pipeline, tag),
              key -> {
                uniforms.setDrawState(
                    pipeline.renderStage(),
                    pipeline.alphaTestRef(),
                    tag.entityId(),
                    tag.blockEntityId(),
                    tag.itemId());
                uniforms.setEntityColor(tag.red(), tag.green(), tag.blue(), tag.alpha());
                try (var allocation =
                    device()
                        .createCommandEncoder()
                        .transientMemory()
                        .allocateGpuMapped(
                            pipeline.translated().uniforms().byteSize(),
                            256,
                            GpuBuffer.USAGE_UNIFORM)) {
                  uniforms.write(
                      pipeline.translated().uniforms(),
                      allocation.data(),
                      pipeline.fullscreen(),
                      pipeline.shadow());
                  return allocation.slice();
                }
              });
      pass.setUniform(UniformLayout.BLOCK_NAME, buffer);
    }
  }

  public void bindTextures(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied) {
    for (var sampler : pipeline.samplerBindings()) bindTexture(pipeline, pass, supplied, sampler);
  }

  /** Only aliases of a changed vanilla material texture need rebinding within a scene pass. */
  public void bindSuppliedTexture(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied,
      String changed) {
    for (var sampler : pipeline.samplerBindings()) {
      boolean affected =
          (sampler.kind() == PackPipeline.SamplerKind.ALBEDO
                      || sampler.kind() == PackPipeline.SamplerKind.MATERIAL)
                  && changed.equals(pipeline.primarySampler())
              || sampler.kind() == PackPipeline.SamplerKind.LIGHTMAP && changed.equals("Sampler2");
      if (affected && textures.custom(pipeline.source().name(), sampler.name()) == null)
        bindTexture(pipeline, pass, supplied, sampler);
    }
  }

  /**
   * Cadence: the sampler state bindTexture always gives this sampler in this program, or null when it depends on the draw (a
   * block or item texture's own sampler). A pass with more than Metal's 16 sampler slots has these compiled into its shader
   * (MetalPipelineSamplers). Must follow bindTexture's choice exactly.
   */
  public GpuSampler samplerGroup(PackPrograms.Source source, PackPipeline.SamplerBinding sampler) {
    String program = source.name();
    if (textures == null) return null;
    if (sampler.comparison()) return textures.comparisonSampler();
    PackTexture custom = textures.custom(program, sampler.name());
    if (custom != null) return textures.samplerFor(custom);
    return switch (sampler.kind()) {
      case COLOR -> mipSampled(source, sampler.index()) ? textures.linear : textures.linearBaseLevel();
      case NORMAL, SPECULAR -> textures.linear;
      case DEPTH, SHADOW_DEPTH -> textures.nearest;
      case SHADOW_COLOR ->
          targets != null && targets.shadowSpecification(sampler.index()).nearest() ? textures.nearest : textures.linear;
      case NOISE -> textures.noiseSampler();
      case MATERIAL, ALBEDO, LIGHTMAP, CUSTOM -> null;
    };
  }

  /** Whether this pass reads colour buffer index with its mipmaps (it declared colortexNMipmapEnabled). */
  private boolean mipSampled(PackPrograms.Source source, int index) {
    try {
      return source.directives().mipmapBuffers().contains(index);
    } catch (IOException failure) {
      return false;
    }
  }

  private boolean mipSampled(PackPipeline pipeline, int index) {
    return mipSampled(pipeline.source(), index);
  }

  private static final java.util.Set<String> UNBOUND_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

  private void bindTexture(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied,
      PackPipeline.SamplerBinding sampler) {
    PackTexture texture = textures.custom(pipeline.source().name(), sampler.name());
    GpuSampler filter = texture == null ? textures.linear : textures.samplerFor(texture);
    GpuTextureView view = null;
    if (texture == null) {
      switch (sampler.kind()) {
        case COLOR -> {
          texture =
              colorFeedback(pipeline, sampler.index()) && copies(pipeline, sampler)
                  ? targets.colorFeedback(sampler.index())
                  : targets.color(sampler.index());  // (or a sampler the code never reads: the buffer itself, no copy)
          if (!mipSampled(pipeline, sampler.index())) filter = textures.linearBaseLevel();
        }
        case DEPTH -> {
          texture =
              depthFeedback(pipeline, sampler.index()) && copies(pipeline, sampler)
                  ? targets.depthFeedback(sampler.index())
                  : targets.depth(sampler.index());
          filter = textures.nearest;
        }
        case SHADOW_DEPTH -> {
          texture = targets.shadowDepth(sampler.index());
          filter = textures.nearest;
        }
        case SHADOW_COLOR -> {
          texture = shadowColorFeedback(pipeline, sampler.index())
              ? targets.shadowColorFeedback(sampler.index())
              : targets.shadowColor(sampler.index());
          if (targets.shadowSpecification(sampler.index()).nearest()) filter = textures.nearest;
        }
        case NOISE -> {
          texture = textures.noise;
          filter = textures.noiseSampler();
        }
        case NORMAL -> texture = textures.normal;
        case SPECULAR -> texture = textures.black;
        case MATERIAL -> {
          if (materialAtlas == null) throw new IllegalStateException("Material atlas not prepared");
          var original = supplied.get(pipeline.primarySampler());
          texture =
              materialAtlas.forAlbedo(
                  original == null || original.texture() == null
                      ? null
                      : original.texture().texture());
          filter = materialAtlas.sampler;
        }
        case ALBEDO, LIGHTMAP -> {
          var original =
              supplied.get(
                  sampler.kind() == PackPipeline.SamplerKind.LIGHTMAP
                      ? "Sampler2"
                      : pipeline.primarySampler());
          if (original != null && original.texture() != null) {
            view = original.texture();
            filter = original.sampler();
            if (sampler.kind() == PackPipeline.SamplerKind.ALBEDO)
              filter = textures.albedoSampler(view, blockAtlas, filter);
          } else texture = textures.white;
        }
        case CUSTOM -> {
          // Cadence: a sampler another mod would supply (Voxy's vxDepthTexTrans, Distant Horizons' dh*: Mellow reads them
          // without asking whether that mod is there): its "nothing here" value, white (depth 1, the far plane) for a depth
          // texture and black for anything else, as with the mod absent. It used to stop the pack.
          texture = sampler.name().toLowerCase(java.util.Locale.ROOT).contains("depth") ? textures.white : textures.black;
          if (UNBOUND_LOGGED.add(sampler.name()))
            LoggerFactory.getLogger("cadence_shaders").info("Sampler {} (another mod's) has no data here: {}", sampler.name(),
                texture == textures.white ? "white (far depth)" : "black");
        }
      }
    }
    if (view == null) view = texture.sampled;
    if (sampler.comparison()) {
      if (textures.hardwareShadowComparison()
          && view.texture().getFormat() != com.mojang.renderpearl.api.GpuFormat.D32_FLOAT)
        throw new UnsupportedOperationException(
            "Hardware shadow comparison requires a depth texture: " + sampler.name());
      filter = textures.comparisonSampler();
    }
    pass.setUniform(sampler.shaderName(), view, filter);
  }

  private void execute(String phase) {
    for (var step : programs.steps(phase)) {
      if (ShaderRenderPass.SKIP_PROGRAMS.contains(step.source().name())) continue;
      if (step.compute()) runCompute(step.source());
      else execute(screenPipeline(step.source()));
    }
  }

  // ---- Cadence: Iris compute programs and the pack's storage resources ----

  private PackStorage storage;
  private boolean setupDone;

  private boolean shadowWritesStorage() {
    var shadow = programs.find("shadow");
    return shadow != null
        && shadow.translated().storage().stream()
            .anyMatch(r -> r.kind() != dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.SAMPLED);
  }
  private final Map<String, dev.cadence.metal.MetalCompute.Kernel> kernels = new java.util.HashMap<>();
  private final Map<String, java.nio.ByteBuffer> kernelUniforms = new java.util.HashMap<>();

  private dev.cadence.metal.MetalCompute.Kernel kernel(PackPrograms.Source source) {
    return kernels.computeIfAbsent(
        source.name(),
        name -> {
          var translated = source.translated();
          java.util.List<dev.cadence.metal.MetalCompute.SamplerState> states = new java.util.ArrayList<>();
          for (var sampler : translated.samplers())
            states.add(samplerState(PackPipeline.SamplerBinding.of(sampler, true), sampler.type()));
          return PackCompute.compile(translated, states, storageSlots(translated), programs.relaxedFragmentMath);
        });
  }

  private dev.cadence.metal.MetalCompute.SamplerState samplerState(PackPipeline.SamplerBinding b, String type) {
    boolean integer = type.startsWith("i") || type.startsWith("u");
    return new dev.cadence.metal.MetalCompute.SamplerState(
        integer || b.kind() == PackPipeline.SamplerKind.DEPTH
            || b.kind() == PackPipeline.SamplerKind.SHADOW_DEPTH && !b.comparison(),
        b.kind() == PackPipeline.SamplerKind.NOISE,
        false,
        b.comparison() && textures.hardwareShadowComparison());
  }

  /** The texture a full-screen or compute program's sampler reads (as bindTexture picks it), defined, as a native handle. */
  private long samplerTexture(String program, PackPipeline.SamplerBinding b) {
    PackTexture t = textures.custom(program, b.name());
    if (t == null)
      t =
          switch (b.kind()) {
            case COLOR -> targets.color(b.index());
            case DEPTH -> targets.depth(b.index());
            case SHADOW_DEPTH -> targets.shadowDepth(b.index());
            case SHADOW_COLOR -> targets.shadowColor(b.index());
            case NOISE -> textures.noise;
            case NORMAL -> textures.normal;
            case SPECULAR -> textures.black;
            default -> textures.white;
          };
    targets.ensureDefined(t);
    return dev.cadence.metal.MetalBridge.textureHandle(t.texture);
  }

  private static final java.util.regex.Pattern COLOR_IMAGE = java.util.regex.Pattern.compile("(shadow)?colorimg([0-9]+)");

  /** The native object behind one of a program's set-1 resources now (0: none). */
  long storageHandle(dev.cadence.shaders.compile.TranslatedProgram.Storage r) {
    return storageHandle(null, r);
  }

  /** (program: the program's name, for a raw 3D custom texture of its stage; null for none) */
  long storageHandle(String program, dev.cadence.shaders.compile.TranslatedProgram.Storage r) {
    // Cadence: a raw 3D custom texture standing in for this sampler in the program's stage (Photon's
    // texture.deferred.depthtex0 = scattering.dat TEXTURE_3D ...): a sampler3D reaches the shader as a set-1 resource
    if (program != null && textures != null && r.kind() == dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.SAMPLED) {
      PackTexture volume = textures.volume(program, r.name());
      if (volume != null) return dev.cadence.metal.MetalBridge.textureHandle(volume.texture);
    }
    if (r.kind() == dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.BUFFER
        && dev.cadence.shaders.cadence.render.DistantTerrain.isFarBuffer(r.bufferIndex()))
      return dev.cadence.shaders.cadence.render.DistantTerrain.bufferHandle(r.bufferIndex());
    if (storage == null) return 0;
    if (r.kind() == dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.BUFFER) return storage.bufferHandle(r.bufferIndex());
    if (storage.hasImage(r.name())) return storage.imageHandle(r.name());
    var m = COLOR_IMAGE.matcher(r.name());
    if (m.matches() && targets != null) {
      int index = Integer.parseInt(m.group(2));
      PackTexture t = m.group(1) != null ? targets.shadowColor(index) : targets.color(index);
      targets.ensureDefined(t);
      targets.wrote(t); // (an image the program may store to: its mip levels go stale)
      return dev.cadence.metal.MetalBridge.textureHandle(t.texture);
    }
    return 0;
  }

  /** A program's set-1 resources for the Metal backend (MetalStorage). */
  java.util.List<dev.cadence.metal.MetalStorage.Slot> storageSlots(dev.cadence.shaders.compile.TranslatedProgram translated) {
    String program = programName(translated.label());
    java.util.List<dev.cadence.metal.MetalStorage.Slot> slots = new java.util.ArrayList<>();
    for (var r : translated.storage()) {
      if (r.binding() >= dev.cadence.metal.MetalStorage.MAX_BINDINGS)
        throw new UnsupportedOperationException(translated.label() + " has more than " + dev.cadence.metal.MetalStorage.MAX_BINDINGS
            + " images and buffer objects");
      // (a raw 3D custom texture is filtered as its .mcmeta asks: Photon's cloud noise linear and repeating, its LUT clamped)
      boolean[] volume = textures == null || r.kind() != dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.SAMPLED
          ? null : textures.volumeFiltering(program, r.name());
      slots.add(
          new dev.cadence.metal.MetalStorage.Slot(
              r.binding(),
              r.kind() == dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.BUFFER,
              r.kind() == dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.SAMPLED,
              volume != null ? volume[0] : r.type().startsWith("i") || r.type().startsWith("u"),
              volume != null && volume[1],
              () -> storageHandle(program, r)));
    }
    return slots;
  }

  /** The program name in a translation label ("world0/deferred1", "pack/world0/gbuffers_terrain/early_alpha_demote"). */
  private static String programName(String label) {
    String l = label.endsWith("/early_alpha_demote") ? label.substring(0, label.length() - "/early_alpha_demote".length()) : label;
    int slash = l.lastIndexOf('/');
    return l.substring(slash + 1).replace(" (Cadence)", "");
  }

  private void runCompute(PackPrograms.Source source) {
    if (ShaderRenderPass.SKIP_PROGRAMS.contains(source.name())) return;
    var translated = source.translated();
    var kernel = kernel(source);
    suspend();
    java.nio.ByteBuffer data =
        kernelUniforms.computeIfAbsent(source.name(), n -> translated.uniforms().allocate());
    if (translated.uniforms().byteSize() > 0) uniforms.write(translated.uniforms(), data, true, false);
    long[] textures = new long[translated.samplers().size()];
    for (int i = 0; i < textures.length; i++)
      textures[i] = samplerTexture(source.name(), PackPipeline.SamplerBinding.of(translated.samplers().get(i), true));
    int[] groups = PackCompute.groups(source, translated, frameWidth, frameHeight);
    try (var pass = dev.cadence.metal.MetalCompute.begin()) {
      pass.kernel(kernel);
      if (translated.uniforms().byteSize() > 0) pass.bytes(0, data);
      for (int i = 0; i < textures.length; i++) pass.texture(i, textures[i]);
      for (var r : translated.storage()) {
        long h = storageHandle(source.name(), r);
        if (h == 0) continue;
        if (r.kind() == dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.BUFFER)
          pass.buffer(dev.cadence.metal.MetalStorage.BUFFER_BASE + r.binding(), h);
        else pass.texture(dev.cadence.metal.MetalStorage.TEXTURE_BASE + r.binding(), h);
      }
      java.util.List<Integer> local = translated.drawBuffers();
      pass.dispatch(groups[0], groups[1], groups[2], local.get(0), local.get(1), local.get(2));
    }
    profile("compute." + source.name() + ".end");
  }

  private void execute(PackPipeline pipeline) {
    // Cadence: a screen pass that writes no buffer (Bliss has one whose writes are all switched off) changes nothing
    if (!pipeline.source().name().equals("final")
        && pipeline.translated().drawBuffers().stream().noneMatch(index -> index >= 0)) return;
    PackPipeline sparse = sparseScreens.get(pipeline);
    if (sparse != null) {
      if (uniforms.matchesMatrix(MatrixUniformSpecialization.PROJECTION_INVERSE)) {
        pipeline = sparse;
        sparseProjectionPasses++;
      } else sparseProjectionFallbacks++;
    }
    profile("pass." + pipeline.source().name() + ".begin");
    prepare(pipeline);
    try (RenderPass pass = open(pipeline, fullscreenDiscardMask(pipeline))) {
      pass.setPipeline(pipeline.compiled());
      bind(pipeline, pass, Map.of());
      pass.draw(6, 1, 0, 0); // the full-screen quad (two triangles; ShaderCompatibilityCompiler FULLSCREEN)
    }
    if (!pipeline.source().name().equals("final") && !pipeline.shadow())
      targets.flip(flipsAfter(pipeline.source().name(), pipeline.translated().drawBuffers()));
    profile("pass." + pipeline.source().name() + ".end");
  }

  /**
   * Cadence: the buffers that change sides after a screen pass: the ones it drew into, then the pack's explicit
   * flip.PASS.BUFFER directives (Iris): true flips a buffer the pass didn't draw into (Photon flips colortex6 after
   * composite1, so later passes read the ambient occlusion its other side still holds), false keeps a drawn one from flipping.
   */
  private java.util.List<Integer> flipsAfter(String pass, java.util.List<Integer> drawn) {
    Map<Integer, Boolean> explicit = explicitFlips().get(pass);
    if (explicit == null) return drawn;
    java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>(drawn);
    explicit.forEach((index, flip) -> {
      if (flip) out.add(index);
      else out.remove(index);
    });
    return java.util.List.copyOf(out);
  }

  private Map<String, Map<Integer, Boolean>> explicitFlips;

  private Map<String, Map<Integer, Boolean>> explicitFlips() {
    if (explicitFlips != null) return explicitFlips;
    Map<String, Map<Integer, Boolean>> out = new java.util.HashMap<>();
    for (var property : properties.values().entrySet()) {
      String[] key = property.getKey().split("\\.");
      if (key.length != 3 || !key[0].equals("flip")) continue;
      String value = property.getValue().strip();
      boolean flip = value.equals("true") || value.equals("1");
      if (!flip && !value.equals("false") && !value.equals("0")) continue;
      try {
        out.computeIfAbsent(key[1], k -> new java.util.HashMap<>()).put(dev.cadence.shaders.pack.ShaderDirectives.colorBufferIndex(key[2]), flip);
      } catch (dev.cadence.shaders.pack.ShaderPackException e) {
        // not a colour buffer: ignored, as Iris does
      }
    }
    return explicitFlips = out;
  }

  private int fullscreenDiscardMask(PackPipeline pipeline) {
    if (!DISCARD_FULLSCREEN_LOADS
        || !pipeline.fullscreen()
        || pipeline.shadow()
        || pipeline.depth()
        || !device()
            .getDeviceInfo()
            .underlyingExtensions()
            .contains(MetalPassHints.DISCARD_COLOR_CAPABILITY)) return 0;
    // Only execute() calls this: PackPipeline.compile(original=null) owns triangles, no depth,
    // blending or culling and WRITE_ALL; execute owns draw(3), full viewport and no scissor.
    // Backend validation additionally checks mip/layer/area bounds and explicit clears.
    return screenOverwrites
        .computeIfAbsent(
            pipeline,
            candidate -> {
              var result = FullscreenOverwrite.analyze(candidate.translated());
              LoggerFactory.getLogger("cadence_shaders")
                  .info(
                      "Fullscreen load discard {}: mask={}, {}",
                      candidate.source().name(),
                      result.colorMask(),
                      result.reason());
              return result;
            })
        .colorMask();
  }

  private void profile(String boundary) {
    if (profiler != null) profiler.mark(boundary);
  }

  private void disposePipelines() {
    geometry.values().forEach(PackPipeline::close);
    geometry.clear();
    if (distantPipeline != null) distantPipeline.close();
    distantPipeline = null;
    distantFailed = false;
    screens.values().forEach(PackPipeline::close);
    screens.clear();
    sparseScreens.values().forEach(PackPipeline::close);
    sparseScreens.clear();
    screenOverwrites.clear();
    frameBuffers.clear();
  }

  private void disposeGraph() {
    ShadowRenderer.discardSelection();
    unusedMipmaps = null;
    disposePipelines();
    if (targets != null) {
      targets.close();
      targets = null;
    }
    if (textures != null) {
      textures.close();
      textures = null;
    }
    if (mipmaps != null) {
      mipmaps.close();
      mipmaps = null;
    }
    if (depthMerge != null) {
      depthMerge.close();
      depthMerge = null;
    }
    if (programs != null) {
      programs.close();
      programs = null;
    }
    for (var k : kernels.values()) dev.cadence.metal.MetalCompute.release(k.pipeline());
    kernels.clear();
    kernelUniforms.clear();
    if (storage != null) {
      storage.close();
      storage = null;
    }
  }

  /** Cadence: the reduced-size final target and the upscale pass, made or freed to match the current scale. */
  private void prepareScaledOutput(com.mojang.blaze3d.pipeline.RenderTarget main) {
    boolean scaled = frameWidth != main.width || frameHeight != main.height;
    if (!scaled) {
      releaseScaledOutput();
      return;
    }
    if (scaledOutput != null
        && scaledOutput.getWidth(0) == frameWidth
        && scaledOutput.getHeight(0) == frameHeight) return;
    releaseScaledOutput();
    var format = main.getColorTexture().getFormat();
    scaledOutput =
        device()
            .createTexture(
                "Cadence render-scale output",
                com.mojang.renderpearl.api.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                    | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_TEXTURE_BINDING
                    | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_SRC,
                format,
                frameWidth,
                frameHeight,
                1,
                1);
    scaledOutputView = device().createTextureView(scaledOutput);
    float sharpen = dev.cadence.shaders.cadence.render.RenderScale.sharpenFor(dev.cadence.shaders.cadence.render.RenderScale.get());
    if (upscale == null || upscale.sharpen() != sharpen) {
      if (upscale != null) upscale.close();
      upscale = new PackUpscale(device(), format, sharpen, dev.cadence.shaders.cadence.render.RenderScale.fastUpscale());
    }
    LoggerFactory.getLogger("cadence_shaders")
        .info("Cadence render scale: pack frame {}x{} upscaled to {}x{}", frameWidth, frameHeight, main.width, main.height);
  }

  private void releaseScaledOutput() {
    if (scaledOutputView != null) scaledOutputView.close();
    if (scaledOutput != null) scaledOutput.close();
    scaledOutputView = null;
    scaledOutput = null;
  }

  @Override
  public void close() {
    closedRuntime = true;
    PipelineMemory.maybeSave(SESSION_VARIANTS, true);
    if (warm != null) {
      warm.thenAccept(PackPrograms::close);
      warm = null;
    }
    releaseScaledOutput();
    if (upscale != null) {
      upscale.close();
      upscale = null;
    }
    ShadowRenderer.discardSelection();
    active = false;
    dev.cadence.shaders.cadence.render.DistantTerrain.want(false);
    suspend();
    disposeGraph();
    if (materialAtlas != null) {
      materialAtlas.close();
      materialAtlas = null;
    }
    OriginalPipelines.clear();
    if (profiler != null) profiler.close();
  }

  record Variant(
      RenderPipeline original, boolean hand, boolean shadow, boolean blockEntity) {}

  private record UniformKey(PackPipeline pipeline, FeatureDrawContext.DrawTag tag) {
    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(pipeline) + tag.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof UniformKey key && pipeline == key.pipeline && tag.equals(key.tag);
    }
  }
}
