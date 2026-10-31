// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.cadence.scene.SceneExtractionRequest;
import dev.cadence.scene.SceneFrustumVolume;
import dev.cadence.scene.SceneGeneration;
import dev.cadence.scene.SceneSelection;
import dev.cadence.scene.SceneSelectionTicket;
import dev.cadence.scene.SceneViewRequest;
import dev.cadence.scene.SceneViews;
import dev.cadence.scene.SceneVolume;
import dev.cadence.shaders.pack.ShaderPackException;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.EnumMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.LoggerFactory;

/** Reuses uploaded vanilla chunk meshes and prepared features from the light's point of view. */
public final class ShadowRenderer {
  private static final EnumMap<
          SceneExtractionRequest.Purpose, ObjectArrayList<SectionRenderDispatcher.RenderSection>>
      extractionCache = new EnumMap<>(SceneExtractionRequest.Purpose.class);
  private static LevelRenderer extractionRenderer;

  /** Cadence (WorldClosed): the last frame's extraction lists hold the old world's sections. */
  public static void releaseWorld() {
    extractionCache.clear();
    extractionRenderer = null;
  }
  // Cadence: on by default (applied only to audited packs, see ShadowCullingEligibility; measured -5 ms/frame of GPU on
  // BSL at RD 12, 48% of shadow sections culled); -Dcadence.shaders.shadowReceiverCulling=false turns it off. Switchable
  // at runtime (self-test A/B); read once per frame.
  private static volatile boolean RECEIVER_CULLING =
      !"false".equals(System.getProperty("cadence.shaders.shadowReceiverCulling"));

  public static void setReceiverCulling(boolean on) {
    RECEIVER_CULLING = on;
  }

  public static boolean receiverCulling() {
    return RECEIVER_CULLING;
  }

  /**
   * Cadence: whether receiver culling runs for this pack: audited (BSL), or verified on this machine by the pack check
   * (PackCheck); while a check runs, the check decides.
   */
  public static boolean cullingFor(ShadowCullingEligibility.Result eligibility) {
    Boolean forced = dev.cadence.shaders.cadence.packcheck.PackCheck.forcedCulling();
    if (forced != null) return RECEIVER_CULLING && forced;
    return RECEIVER_CULLING && (eligibility.eligible() || dev.cadence.shaders.cadence.packcheck.PackCheck.verified());
  }
  private static final boolean TIGHT_RECEIVER_PADDING =
      Boolean.getBoolean("cadence.shaders.tightShadowReceiverPadding");
  private static final boolean ASYNC_SELECTION =
      Boolean.getBoolean("cadence.scene.asyncSelection");
  // Audited BSL defaults: <13.895 blocks of filter reach, <=6 blocks of raster interpolation
  // displacement for section-bounded triangles, <1 block waving, and <.128 reverse-depth bias.
  // The 32-block diagnostic retains numeric slack; 64 remains the default. See the source audit.
  private static final double RECEIVER_PADDING = 64;
  private static CullingStats lastCullingStats =
      new CullingStats(false, false, 0, 0, 0, 0, RECEIVER_PADDING, "not-rendered");
  private static long cullingFrames;
  private static PendingSelection pendingSelection;
  private static long selectionFrame, frameCaptureNanos;
  private static long overlapRequested, overlapSubmitted, overlapUsed, overlapStale;
  private static long overlapCaptureNanos, overlapConsumeNanos;

  private ShadowRenderer() {}

  /**
   * Counts refer to compiled terrain inside the original light frustum. selectionCpuMs is elapsed
   * main-thread work, not thread CPU time. Control retains its historical timer after light-frustum
   * construction; async mode includes capture (including frustum construction) plus consumption,
   * never worker latency or the time between prefetch and use.
   */
  public record CullingStats(
      boolean requested,
      boolean applied,
      int candidates,
      int submitted,
      int rejected,
      double selectionCpuMs,
      double receiverPadding,
      String eligibility) {}

  /** Used means a valid ticket reached the provider; its ready counter proves worker-result use. */
  public record OverlapStats(
      boolean enabled,
      long requested,
      long submitted,
      long used,
      long stale,
      long captureNanos,
      long consumeNanos) {}

  public static OverlapStats overlapStats() {
    return new OverlapStats(
        ASYNC_SELECTION,
        overlapRequested,
        overlapSubmitted,
        overlapUsed,
        overlapStale,
        overlapCaptureNanos,
        overlapConsumeNanos);
  }

  /** Called before any beginFrame early exit, including a world which has just been unloaded. */
  public static void beginFrame() {
    discardSelection();
    selectionFrame++;
    frameCaptureNanos = 0;
  }

  public static void discardSelection() {
    PendingSelection previous = pendingSelection;
    pendingSelection = null;
    if (previous != null && previous.ticket() != null) previous.ticket().close();
  }

  /** After camera recentering, immediately before prepareFrame can wait for its transform ring. */
  public static void prefetchSelection(LevelRenderer renderer, ViewArea area) {
    if (!ASYNC_SELECTION) return;
    discardSelection();
    ShaderRuntime runtime = ShaderRuntime.get();
    if (!ShaderRuntime.isRendering()
        || runtime == null
        || area == null
        || runtime.programs() == null
        || runtime.programs().find("shadow") == null) return;
    overlapRequested++;
    long started = System.nanoTime();
    try {
      SelectionInputs inputs = captureInputs(runtime);
      var eligibility = runtime.programs().shadowCullingEligibility;
      boolean receiverCulling = cullingFor(eligibility);
      double padding = receiverPadding(TIGHT_RECEIVER_PADDING, receiverCulling);
      ShadowCasterVolume receiver =
          receiverCulling
              ? ShadowCasterVolume.create(
                  inputs.projection, inputs.modelView, inputs.light, padding)
              : null;
      SceneGeneration generation = SceneViews.generation();
      var request =
          new SceneViewRequest(
              area,
              generation,
              "shadow-render",
              new SceneFrustumVolume(inputs.frustum(), 0),
              receiver == null ? null : new ReceiverRefinement(receiver, inputs.receiverOrigin),
              true);
      SceneSelectionTicket ticket = SceneViews.prefetch(request);
      if (ticket != null) overlapSubmitted++;
      pendingSelection =
          new PendingSelection(
              renderer,
              area,
              runtime,
              runtime.programs(),
              generation,
              selectionFrame,
              inputs,
              request,
              ticket,
              receiver != null && receiver.planeCount() > 0,
              padding,
              eligibility.reason());
    } finally {
      long elapsed = System.nanoTime() - started;
      frameCaptureNanos += elapsed;
      overlapCaptureNanos += elapsed;
    }
  }

  static double receiverPadding(boolean tightRequested, boolean eligible) {
    return tightRequested && eligible ? 32 : RECEIVER_PADDING;
  }

  public static CullingStats cullingStats() {
    return lastCullingStats;
  }

  public static void updateMatrices(ShaderRuntime runtime) throws ShaderPackException {
    float distance = runtime.programs().globals.floatConstant("shadowDistance", 160);
    if (!(distance > 0)) throw new ShaderPackException("Shadow distance must be positive");
    Matrix4f view = lightView(runtime.uniforms().shadowDirectionWorld(), distance);
    float interval = runtime.programs().globals.floatConstant("shadowIntervalSize", 2);
    var camera =
        Minecraft.getInstance()
            .gameRenderer
            .gameRenderState()
            .levelRenderState
            .cameraRenderState
            .pos;
    if (runtime.programs().properties.get("shadow.texelSnap", "false").equals("true")) {
      snapToTexels(
          view,
          camera.x,
          camera.y,
          camera.z,
          distance,
          runtime.programs().globals.intConstant("shadowMapResolution", 1024));
    } else if (interval > 0) {
      // World-anchored sampling prevents every tiny camera movement from sliding the shadow grid.
      view.translate(
          (float) (camera.x - Math.floor(camera.x / interval) * interval),
          (float) (camera.y - Math.floor(camera.y / interval) * interval),
          (float) (camera.z - Math.floor(camera.z / interval) * interval));
    }
    Matrix4f projection =
        new Matrix4f().ortho(-distance, distance, -distance, distance, 0.05f, distance * 4);
    runtime.uniforms().setShadowMatrices(projection, view);
  }

  /** Never choose another light here: the uniform and the depth map must describe the same one. */
  static Matrix4f lightView(Vector3fc direction, float distance) {
    Vector3f up = Math.abs(direction.y()) > 0.99f ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
    return new Matrix4f()
        .lookAt(
            direction.x() * distance * 2,
            direction.y() * distance * 2,
            direction.z() * distance * 2,
            0,
            0,
            0,
            up.x,
            up.y,
            up.z);
  }

  /** Snap in the light's XY basis; double intermediates retain precision far from world origin. */
  static void snapToTexels(Matrix4f view, double x, double y, double z, float distance, int size) {
    double texel = 2.0 * distance / size;
    double lx = view.m00() * x + view.m10() * y + view.m20() * z;
    double ly = view.m01() * x + view.m11() * y + view.m21() * z;
    view.m30(view.m30() + (float) (lx - Math.rint(lx / texel) * texel));
    view.m31(view.m31() + (float) (ly - Math.rint(ly / texel) * texel));
  }

  public static Frustum frustum() {
    ShaderRuntime runtime = ShaderRuntime.get();
    if (runtime == null || runtime.programs() == null || runtime.programs().find("shadow") == null)
      return null;
    Frustum result =
        new Frustum(
            runtime.uniforms().shadowModelView(),
            new Matrix4f(runtime.uniforms().shadowProjection()));
    var camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
    result.prepare(camera.x, camera.y, camera.z);
    return result;
  }

  public static void beginExtraction() {
    discardSelection();
    extractionRenderer = null;
    extractionCache.clear();
  }

  public static Frustum withShadowFrustum(Frustum cameraFrustum) {
    Frustum light = frustum();
    if (light == null) return cameraFrustum;
    return new UnionFrustum(cameraFrustum, light);
  }

  /**
   * The camera's view or the light's (an entity is extracted for either pass). Cadence: remembers which of the two
   * accepted the boxes asked since {@link #reset} (EntityCull decides each entity's passes from it).
   */
  public static final class UnionFrustum extends Frustum {
    private final Frustum camera, light;
    public boolean cameraHit, lightHit, asked;

    UnionFrustum(Frustum camera, Frustum light) {
      super(camera);
      this.camera = camera;
      this.light = light;
    }

    public void reset() {
      cameraHit = lightHit = asked = false;
    }

    @Override
    public boolean isVisible(AABB bounds) {
      asked = true;
      boolean c = camera.isVisible(bounds);
      boolean l = light.isVisible(bounds.inflate(16));
      cameraHit |= c;
      lightHit |= l;
      return c || l;
    }
  }

  /**
   * Cadence: the shadow pass's own section test (the light's view box, then upstream's audited receiver volume when the
   * pack is eligible and receiver culling is on) for an entity or block entity box, at extraction. Uses the matrices and
   * camera position the last shadow selection used; the receiver volume's 64-block padding covers one frame of motion.
   * null: no shadow pass. Built once per extraction.
   */
  public static java.util.function.Predicate<AABB> casterTest() {
    ShaderRuntime runtime = ShaderRuntime.get();
    Frustum light = frustum();
    if (runtime == null || light == null) return null;
    boolean receiverCulling = cullingFor(runtime.programs().shadowCullingEligibility);
    ShadowCasterVolume receiver =
        receiverCulling
            ? ShadowCasterVolume.create(
                runtime.uniforms().projection(),
                runtime.uniforms().modelView(),
                runtime.uniforms().shadowDirectionWorld(),
                receiverPadding(TIGHT_RECEIVER_PADDING, true))
            : null;
    if (receiver == null || receiver.planeCount() == 0) return bounds -> light.isVisible(bounds.inflate(16));
    Vec3 origin =
        Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
    SceneVolume refinement = new ReceiverRefinement(receiver, origin);
    return bounds -> light.isVisible(bounds.inflate(16)) && refinement.intersects(bounds);
  }

  /** Include off-camera casters in normal asynchronous chunk compilation and feature extraction. */
  public static ObjectArrayList<SectionRenderDispatcher.RenderSection> extractionSections(
      LevelRenderer renderer,
      SceneExtractionRequest.Purpose purpose,
      SectionUpdateTracker updateTracker) {
    var normal = renderer.visibleSections();
    Frustum shadowFrustum = frustum();
    ViewArea area = renderer.viewArea();
    if (shadowFrustum == null || area == null) return normal;
    if (extractionRenderer != renderer) extractionCache.clear();
    var cached = extractionCache.get(purpose);
    if (cached != null) return cached;
    // Keep one section of padding for extraction/render camera motion. Each vanilla loop needs
    // a different sparse subset; clean sections and featureless meshes have no extraction work.
    var selected =
        SceneViews.selectExtraction(
            new SceneExtractionRequest(
                new SceneViewRequest(
                    area,
                    SceneViews.generation(),
                    "shadow-extraction-" + purpose.name(),
                    new SceneFrustumVolume(shadowFrustum, 16),
                    null,
                    false),
                normal,
                purpose,
                updateTracker,
                purpose == SceneExtractionRequest.Purpose.DIRTY_SECTIONS
                    ? dev.cadence.shaders.cadence.shadow.ShadowCompileCull.excludedNodes(renderer)
                    : null));
    var result = new ObjectArrayList<>(selected);
    // Cadence: of the light-view sections, compile only casters that can shadow a receiver (ShadowCompileCull).
    if (purpose == SceneExtractionRequest.Purpose.DIRTY_SECTIONS)
      result = dev.cadence.shaders.cadence.shadow.ShadowCompileCull.filter(renderer, normal, result);
    extractionCache.put(purpose, result);
    extractionRenderer = renderer;
    return result;
  }

  public static void render(
      LevelRenderer renderer,
      ViewArea area,
      FeatureRenderDispatcher.PreparedFrame features,
      GpuBufferSlice terrainFog) {
    ShaderRuntime runtime = ShaderRuntime.get();
    boolean shadowStarted = false;
    // Cadence: test-only sabotage of the pack check's 'on' runs (proves a broken speed-up is caught)
    if (runtime != null && Boolean.getBoolean("cadence.packCheckSabotage")
        && Boolean.TRUE.equals(dev.cadence.shaders.cadence.packcheck.PackCheck.forcedCulling())) {
      discardSelection();
      runtime.skipShadow();
      return;
    }
    if (runtime != null && dev.cadence.shaders.cadence.shadow.ShadowReuse.reusing()) {
      // Cadence: this frame keeps the last shadow map (ShadowReuse).
      discardSelection();
      runtime.skipShadow();
      return;
    }
    try {
      if (runtime == null || area == null || !runtime.beginShadow()) return;
      shadowStarted = true;
      SelectionResult selected = selectForRender(renderer, area, runtime);
      SceneSelection selection = selected.selection();
      if (Boolean.getBoolean("cadence.shadowSelectionDiag"))
        dev.cadence.shaders.cadence.shadow.ShadowCompileCull.countShadowSelection(selection.sections());
      lastCullingStats =
          new CullingStats(
              RECEIVER_CULLING,
              selected.receiverApplied(),
              selection.candidates(),
              selection.sections().size(),
              selection.candidates() - selection.sections().size(),
              RECEIVER_CULLING || ASYNC_SELECTION ? selected.elapsedNanos() * 1.0e-6 : 0,
              selected.padding(),
              selected.eligibility());
      // (statistics for tests: -Dcadence.logStats=true or a self-test run; silent in play)
      if (RECEIVER_CULLING && dev.cadence.metal.backend.FrameHints.LOG_STATS && cullingFrames++ % 120 == 0) {
        LoggerFactory.getLogger("cadence_shaders")
            .info("Shadow receiver culling: {}", lastCullingStats);
      }
      // Cadence: far columns drawn as height-field proxies instead of their sections (ShadowProxies).
      var proxies = dev.cadence.shaders.cadence.shadow.ShadowProxies.get();
      boolean proxyFrame = false;
      if (proxies != null) {
        Vec3 cam =
            Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet wanted = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (var section : selection.sections()) {
          long node = section.getSectionNode();
          int sx = net.minecraft.core.SectionPos.x(node), sz = net.minecraft.core.SectionPos.z(node);
          if (proxies.far(cam.x, cam.z, sx, sz)) wanted.add(net.minecraft.world.level.ChunkPos.pack(sx, sz));
        }
        if (!wanted.isEmpty()) {
          var asProxy = proxies.prepare(wanted, cam.x, cam.z);
          if (!asProxy.isEmpty()) {
            var near = new java.util.ArrayList<SectionRenderDispatcher.RenderSection>(selection.sections().size());
            for (var section : selection.sections()) {
              long node = section.getSectionNode();
              if (!asProxy.contains(
                  net.minecraft.world.level.ChunkPos.pack(
                      net.minecraft.core.SectionPos.x(node), net.minecraft.core.SectionPos.z(node))))
                near.add(section);
            }
            selection =
                new SceneSelection(
                    selection.area(),
                    selection.generation(),
                    selection.viewId(),
                    near,
                    selection.candidates(),
                    selection.boundsTests(),
                    selection.coarseTests(),
                    selection.providerId());
            proxyFrame = true;
          }
        }
      }
      // Preparation consumes this view's ordered selection without replacing camera visibility.
      // Cadence: draws prepared for the light's orthographic view (FaceCull).
      var toLight = runtime.uniforms().shadowDirectionWorld();
      dev.cadence.shaders.cadence.facecull.FaceCull.shadowView(toLight.x(), toLight.y(), toLight.z());
      ChunkSectionsToRender chunks;
      try {
        chunks = SceneViews.prepare(renderer, selection, runtime.uniforms().cameraViewRotation(), false);
      } finally {
        dev.cadence.shaders.cadence.facecull.FaceCull.cameraView();
      }
      var minecraft = Minecraft.getInstance();
      var atlas =
          minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
      var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
      RenderSystem.setShaderFog(terrainFog);
      try (ShaderRenderPass pass = new ShaderRenderPass(runtime)) {
        RenderSystem.bindDefaultUniforms(pass);
        chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false);
        if (proxyFrame)
          proxies.draw(
              pass,
              ((dev.cadence.shaders.cadence.mixin.shadow.ChunkSectionsToRenderAccessor) chunks)
                  .cadence$terrainTransform(),
              sampler,
              atlas);
        features.executeSolid(pass);
        pass.suspend();
        if (runtime.programs().readsSampler("shadowtex1")) runtime.targets().copyShadowDepth();
        if (!runtime.programs().properties.get("shadow.translucent", "true").equals("false")) {
          chunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, sampler, atlas, false);
          features.executeTranslucent(pass);
          features.executeTranslucentAfterTerrain(pass);
        }
      }
    } finally {
      discardSelection();
      if (shadowStarted) runtime.endShadow();
    }
  }

  private static SelectionResult selectForRender(
      LevelRenderer renderer, ViewArea area, ShaderRuntime runtime) {
    if (!ASYNC_SELECTION) return selectSynchronously(area, runtime);
    long started = System.nanoTime();
    PendingSelection prepared = pendingSelection;
    pendingSelection = null;
    SelectionResult result;
    long elapsed;
    try {
      if (prepared != null && prepared.matches(renderer, area, runtime)) {
        if (prepared.ticket() != null) overlapUsed++;
        SceneSelection selection = SceneViews.select(prepared.request(), prepared.ticket());
        result =
            new SelectionResult(
                selection,
                prepared.receiverApplied(),
                prepared.padding(),
                prepared.eligibility(),
                0);
      } else {
        if (prepared != null) overlapStale++;
        result = selectSynchronously(area, runtime);
      }
    } finally {
      if (prepared != null && prepared.ticket() != null) prepared.ticket().close();
      elapsed = System.nanoTime() - started;
      overlapConsumeNanos += elapsed;
    }
    return new SelectionResult(
        result.selection(),
        result.receiverApplied(),
        result.padding(),
        result.eligibility(),
        frameCaptureNanos + elapsed);
  }

  private static SelectionResult selectSynchronously(ViewArea area, ShaderRuntime runtime) {
    // Keep the control's original math/order and timing boundary: the primary frustum was always
    // constructed before selectionCpuMs started. The async timer deliberately counts capture too.
    Frustum lightFrustum = frustum();
    var eligibility = runtime.programs().shadowCullingEligibility;
    boolean receiverCulling = cullingFor(eligibility);
    double padding = receiverPadding(TIGHT_RECEIVER_PADDING, receiverCulling);
    long started = RECEIVER_CULLING ? System.nanoTime() : 0;
    ShadowCasterVolume receiver =
        receiverCulling
            ? ShadowCasterVolume.create(
                runtime.uniforms().projection(),
                runtime.uniforms().modelView(),
                runtime.uniforms().shadowDirectionWorld(),
                padding)
            : null;
    Vec3 camera =
        Minecraft.getInstance()
            .gameRenderer
            .gameRenderState()
            .levelRenderState
            .cameraRenderState
            .pos;
    SceneVolume refinement = receiver == null ? null : new ReceiverRefinement(receiver, camera);
    SceneSelection selection =
        SceneViews.select(
            new SceneViewRequest(
                area,
                SceneViews.generation(),
                "shadow-render",
                new SceneFrustumVolume(lightFrustum, 0),
                refinement,
                true));
    return new SelectionResult(
        selection,
        receiver != null && receiver.planeCount() > 0,
        padding,
        eligibility.reason(),
        RECEIVER_CULLING ? System.nanoTime() - started : 0);
  }

  private record SelectionResult(
      SceneSelection selection,
      boolean receiverApplied,
      double padding,
      String eligibility,
      long elapsedNanos) {}

  private static SelectionInputs captureInputs(ShaderRuntime runtime) {
    var minecraft = Minecraft.getInstance();
    var uniforms = runtime.uniforms();
    return new SelectionInputs(
        uniforms.projection(),
        uniforms.modelView(),
        uniforms.shadowProjection(),
        uniforms.shadowModelView(),
        uniforms.shadowDirectionWorld(),
        minecraft.gameRenderer.mainCamera().position(),
        minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos);
  }

  private record PendingSelection(
      LevelRenderer renderer,
      ViewArea area,
      ShaderRuntime runtime,
      PackPrograms programs,
      SceneGeneration generation,
      long frame,
      SelectionInputs inputs,
      SceneViewRequest request,
      SceneSelectionTicket ticket,
      boolean receiverApplied,
      double padding,
      String eligibility) {
    boolean matches(LevelRenderer currentRenderer, ViewArea currentArea, ShaderRuntime current) {
      if (renderer != currentRenderer
          || area != currentArea
          || runtime != current
          || programs != current.programs()
          || frame != selectionFrame
          || !generation.equals(SceneViews.generation())) return false;
      var minecraft = Minecraft.getInstance();
      var uniforms = current.uniforms();
      return inputs.matches(
          uniforms.projection(),
          uniforms.modelView(),
          uniforms.shadowProjection(),
          uniforms.shadowModelView(),
          uniforms.shadowDirectionWorld(),
          minecraft.gameRenderer.mainCamera().position(),
          minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos);
    }
  }

  /**
   * Owns every mutable input; package-local seam verifies copying and exact invalidation on CPU.
   */
  static final class SelectionInputs {
    private final Matrix4f projection, modelView, shadowProjection, shadowModelView;
    private final Vector3f light;
    private final Vec3 primaryOrigin, receiverOrigin;

    SelectionInputs(
        Matrix4fc projection,
        Matrix4fc modelView,
        Matrix4fc shadowProjection,
        Matrix4fc shadowModelView,
        Vector3fc light,
        Vec3 primaryOrigin,
        Vec3 receiverOrigin) {
      this.projection = new Matrix4f(projection);
      this.modelView = new Matrix4f(modelView);
      this.shadowProjection = new Matrix4f(shadowProjection);
      this.shadowModelView = new Matrix4f(shadowModelView);
      this.light = new Vector3f(light);
      this.primaryOrigin = new Vec3(primaryOrigin.x, primaryOrigin.y, primaryOrigin.z);
      this.receiverOrigin = new Vec3(receiverOrigin.x, receiverOrigin.y, receiverOrigin.z);
    }

    Frustum frustum() {
      Frustum result = new Frustum(shadowModelView, shadowProjection);
      result.prepare(primaryOrigin.x, primaryOrigin.y, primaryOrigin.z);
      return result;
    }

    boolean matches(
        Matrix4fc projection,
        Matrix4fc modelView,
        Matrix4fc shadowProjection,
        Matrix4fc shadowModelView,
        Vector3fc light,
        Vec3 primaryOrigin,
        Vec3 receiverOrigin) {
      return same(this.projection, projection)
          && same(this.modelView, modelView)
          && same(this.shadowProjection, shadowProjection)
          && same(this.shadowModelView, shadowModelView)
          && same(this.light.x, light.x())
          && same(this.light.y, light.y())
          && same(this.light.z, light.z())
          && same(this.primaryOrigin, primaryOrigin)
          && same(this.receiverOrigin, receiverOrigin);
    }

    private static boolean same(Matrix4fc a, Matrix4fc b) {
      return same(a.m00(), b.m00())
          && same(a.m01(), b.m01())
          && same(a.m02(), b.m02())
          && same(a.m03(), b.m03())
          && same(a.m10(), b.m10())
          && same(a.m11(), b.m11())
          && same(a.m12(), b.m12())
          && same(a.m13(), b.m13())
          && same(a.m20(), b.m20())
          && same(a.m21(), b.m21())
          && same(a.m22(), b.m22())
          && same(a.m23(), b.m23())
          && same(a.m30(), b.m30())
          && same(a.m31(), b.m31())
          && same(a.m32(), b.m32())
          && same(a.m33(), b.m33());
    }

    private static boolean same(float a, float b) {
      return Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
    }

    private static boolean same(Vec3 a, Vec3 b) {
      return Double.doubleToRawLongBits(a.x) == Double.doubleToRawLongBits(b.x)
          && Double.doubleToRawLongBits(a.y) == Double.doubleToRawLongBits(b.y)
          && Double.doubleToRawLongBits(a.z) == Double.doubleToRawLongBits(b.z);
    }
  }

  /**
   * Receiver planes and Vec3 coordinates are immutable; workers never read camera/runtime state.
   */
  static final class ReceiverRefinement implements SceneVolume {
    private final ShadowCasterVolume receiver;
    private final Vec3 origin;

    ReceiverRefinement(ShadowCasterVolume receiver, Vec3 origin) {
      this.receiver = receiver;
      this.origin = origin;
    }

    @Override
    public boolean intersects(AABB bounds) {
      return receiver.intersects(
          bounds.minX - origin.x,
          bounds.minY - origin.y,
          bounds.minZ - origin.z,
          bounds.maxX - origin.x,
          bounds.maxY - origin.y,
          bounds.maxZ - origin.z);
    }

    @Override
    public SceneVolume workerSnapshot() {
      return this;
    }
  }

  /** UI-like scene features and vanilla blob shadows are not sunlight occluders. */
  public static boolean skipPipeline(RenderPipeline pipeline) {
    String path = pipeline.getLocation().getPath();
    path = path.substring(path.lastIndexOf('/') + 1);
    return path.contains("entity_shadow")
        // Breaking cracks are a decal on the existing caster, not additional solid geometry.
        || path.contains("crumbling")
        || path.contains("text")
        || path.contains("outline")
        || path.contains("lines")
        || path.contains("line_strip")
        || path.contains("debug")
        || path.contains("particle")
        || path.contains("beacon_beam")
        // Both vanilla pipelines use additive LIGHTNING blending (OIT_ADDITIVE in OIT).
        // Their luminous effect volumes do not obstruct the light reaching other geometry.
        || path.equals("lightning")
        || path.equals("dragon_rays")
        // Fused item/entity glint pipelines still draw the caster's base material.
        || pipeline.getShaders().get(ShaderType.VERTEX).getPath().equals("core/glint")
        || path.contains("water_mask")
        || path.contains("weather")
        || path.contains("world_border")
        || path.contains("sky")
        || path.contains("cloud");
  }
}
