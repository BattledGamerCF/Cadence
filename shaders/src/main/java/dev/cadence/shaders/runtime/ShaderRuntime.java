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
  private static ShaderRuntime instance;
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
          future.complete(new PackPrograms(pack, options, dimension, comparison));
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
    instance = runtime;
    // Cadence: lazy clears while a pack runs (BSL: ~1.6-1.75 ms less GPU a frame, pictures alike); off without a pack, where
    // they measured nothing. -Dcadence.metal.lazyClears=true|false fixes it either way.
    if (System.getProperty("cadence.metal.lazyClears") == null) dev.cadence.metal.FrameHints.lazyClears(runtime != null);
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
    dev.cadence.shaders.cadence.RenderScale.frame(
        System.nanoTime(), chunks != null && chunks.getCompileQueueSize() > 32);
    float scale = dev.cadence.shaders.cadence.RenderScale.get();
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
        textures = new PackTextures(device(), pack, programs.properties, shadowComparison);
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
              && dev.cadence.shaders.cadence.ShadowReuse.decide(
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
            dev.cadence.shaders.cadence.ShadowReuse.projection(),
            dev.cadence.shaders.cadence.ShadowReuse.view(cameraPos));
      } else {
        updateShadowMatrices();
        dev.cadence.shaders.cadence.ShadowReuse.drawn(
            targets,
            cameraPos,
            uniforms.shadowDirectionWorld(),
            uniforms.shadowProjection(),
            uniforms.shadowModelView());
      }
      active = true;
      hand = false;
      shadow = false;
      deferred = false;
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

  public void beforeTranslucents() {
    if (!active || shadow || deferred) return;
    suspend();
    profile("world.opaque.end");
    // depthtex1 also receives opaque hand rendering; it must start from scene depth.
    targets.copyDepth(1);
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
          // Cadence: Apple's MetalFX spatial upscaler when chosen (upscaler=metalfx), else (or if it can't) the shader upscale.
          if (!dev.cadence.shaders.cadence.RenderScale.metalFx()
              || !dev.cadence.metal.MetalFxUpscaler.encode(scaledOutputView, mainView))
          {
            if (upscale.fast() != dev.cadence.shaders.cadence.RenderScale.fastUpscale()) {
              float sharpen = dev.cadence.shaders.cadence.RenderScale.sharpenFor(dev.cadence.shaders.cadence.RenderScale.get());
              upscale.close();
              upscale = new PackUpscale(device(), scaledOutput.getFormat(), sharpen, dev.cadence.shaders.cadence.RenderScale.fastUpscale());
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
    return geometry.computeIfAbsent(
        key,
        variant -> {
          SceneProgram selection = SceneProgram.select(original, hand, shadow, blockEntity);
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
                programs.geometry(
                    source, MinecraftVertexAdapter.adapter(original, shadow, hand), alphaTest);
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
          int depthTarget = hand && !selection.name().equals("gbuffers_hand_water") ? 1 : 0;
          return PackPipeline.compile(
              device(),
              source,
              translated,
              original,
              targets,
              false,
              shadow,
              depthTarget,
              selection.stage(),
              alphaTest.reference());
        });
  }

  private PackPipeline screenPipeline(PackPrograms.Source source) {
    return screens.computeIfAbsent(
        source.name(),
        name -> {
          PackPipeline dense =
              PackPipeline.compile(
                  device(),
                  source,
                  source.translated(),
                  null,
                  targets,
                  name.equals("final"),
                  false,
                  0,
                  0,
                  0);
          if (SPARSE_PROJECTION) {
            var candidate =
                MatrixUniformSpecialization.apply(
                    source.translated(), MatrixUniformSpecialization.PROJECTION_INVERSE);
            if (candidate.applied()) {
              try {
                sparseScreens.put(
                    dense,
                    PackPipeline.compile(
                        device(),
                        source,
                        candidate.program(),
                        null,
                        targets,
                        name.equals("final"),
                        false,
                        0,
                        0,
                        0));
              } catch (RuntimeException failure) {
                LoggerFactory.getLogger("cadence_shaders")
                    .warn(
                        "Sparse projection variant {} unavailable; retaining dense shader",
                        name,
                        failure);
              }
            }
          }
          return dense;
        });
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

  /** Cadence: the feedback copies this program reads (made outside a pass: before it opens). */
  private void prepareFeedback(PackPipeline pipeline) {
    for (var sampler : pipeline.samplerBindings()) {
      if (textures.custom(pipeline.source().name(), sampler.name()) != null) continue;
      if (sampler.kind() == PackPipeline.SamplerKind.COLOR && colorFeedback(pipeline, sampler.index()))
        targets.colorFeedback(sampler.index());
      else if (sampler.kind() == PackPipeline.SamplerKind.DEPTH && depthFeedback(pipeline, sampler.index()))
        targets.depthFeedback(sampler.index());
    }
  }

  public boolean requiresReadPreparation(PackPipeline pipeline) {
    for (var sampler : pipeline.samplerBindings()) {
      if (textures.custom(pipeline.source().name(), sampler.name()) != null) continue;
      if (sampler.kind() == PackPipeline.SamplerKind.COLOR && colorFeedback(pipeline, sampler.index())
          && !targets.colorFeedbackReady(sampler.index())) return true;
      if (sampler.kind() == PackPipeline.SamplerKind.DEPTH && depthFeedback(pipeline, sampler.index())
          && !targets.depthFeedbackReady(sampler.index())) return true;
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
      if (target != null) targets.ensureDefined(target);
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
                    tag.blockEntityId());
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
              colorFeedback(pipeline, sampler.index())
                  ? targets.colorFeedback(sampler.index())
                  : targets.color(sampler.index());
          if (!mipSampled(pipeline, sampler.index())) filter = textures.linearBaseLevel();
        }
        case DEPTH -> {
          texture =
              depthFeedback(pipeline, sampler.index())
                  ? targets.depthFeedback(sampler.index())
                  : targets.depth(sampler.index());
          filter = textures.nearest;
        }
        case SHADOW_DEPTH -> {
          texture = targets.shadowDepth(sampler.index());
          filter = textures.nearest;
        }
        case SHADOW_COLOR -> {
          texture = targets.shadowColor(sampler.index());
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
        case CUSTOM ->
            throw new UnsupportedOperationException(
                "Unbound pack sampler " + sampler.name() + " in " + pipeline.source().name());
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
    java.util.List<dev.cadence.metal.MetalStorage.Slot> slots = new java.util.ArrayList<>();
    for (var r : translated.storage()) {
      if (r.binding() >= dev.cadence.metal.MetalStorage.MAX_BINDINGS)
        throw new UnsupportedOperationException(translated.label() + " has more than " + dev.cadence.metal.MetalStorage.MAX_BINDINGS
            + " images and buffer objects");
      slots.add(
          new dev.cadence.metal.MetalStorage.Slot(
              r.binding(),
              r.kind() == dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.BUFFER,
              r.kind() == dev.cadence.shaders.compile.TranslatedProgram.Storage.Kind.SAMPLED,
              r.type().startsWith("i") || r.type().startsWith("u"),
              () -> storageHandle(r)));
    }
    return slots;
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
        long h = storageHandle(r);
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
    if (!pipeline.source().name().equals("final"))
      targets.flip(pipeline.translated().drawBuffers());
    profile("pass." + pipeline.source().name() + ".end");
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
    float sharpen = dev.cadence.shaders.cadence.RenderScale.sharpenFor(dev.cadence.shaders.cadence.RenderScale.get());
    if (upscale == null || upscale.sharpen() != sharpen) {
      if (upscale != null) upscale.close();
      upscale = new PackUpscale(device(), format, sharpen, dev.cadence.shaders.cadence.RenderScale.fastUpscale());
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
    suspend();
    disposeGraph();
    if (materialAtlas != null) {
      materialAtlas.close();
      materialAtlas = null;
    }
    OriginalPipelines.clear();
    if (profiler != null) profiler.close();
  }

  private record Variant(
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
