// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import dev.cadence.shaders.compile.UniformLayout;
import dev.cadence.shaders.pack.CustomUniforms;
import dev.cadence.shaders.pack.ShaderDirectives;
import dev.cadence.shaders.pack.ShaderPackException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.LoggerFactory;

/**
 * Real game values and temporal history shared by pack programs. Pack matrices use conventional
 * OpenGL -1..1 depth; the compiler converts their vertex output to Metal 0..1 depth.
 */
public final class FrameUniforms {
  private static final float[] IDENTITY = new Matrix4f().get(new float[16]);
  /** Cadence: Iris's full-screen projection (its quad's corners are 0..1). */
  private static final float[] FULLSCREEN_ORTHO = new Matrix4f().setOrtho(0, 1, 0, 1, 0, 1).get(new float[16]);
  private static final float[] IDENTITY3 = new Matrix3f().get(new float[9]);
  private static final float[] TEXTURE_MATRICES = textureMatrices();
  /** Cadence (debugging, self-test uniforms=): a uniform's current value as text, or "unset". */
  public String describe(String name) {
    Double scalar = scalars.get(name);
    if (scalar != null) return name + "=" + scalar;
    float[] vector = vectors.get(name);
    return vector != null ? name + "=" + java.util.Arrays.toString(vector) : name + " unset";
  }

  private final Map<String, Double> scalars = new HashMap<>();
  private final Map<String, float[]> vectors = new HashMap<>();
  private final Map<String, float[]> matrices = new HashMap<>();
  private final Matrix4f projection = new Matrix4f();
  private final Matrix4f modelView = new Matrix4f();
  private final Matrix4f cameraViewRotation = new Matrix4f();
  private final Matrix4f cameraEffect = new Matrix4f();
  private final Matrix4f previousProjection = new Matrix4f();
  private final Matrix4f previousModelView = new Matrix4f();
  private final Matrix4f shadowProjection = new Matrix4f();
  private final Matrix4f shadowModelView = new Matrix4f();
  private final Matrix4f capturedProjection = new Matrix4f();
  private final Vector3f sunWorld = new Vector3f();
  private final Vector3f shadowWorld = new Vector3f();
  private ClientLevel previousLevel;

  /** Cadence (WorldClosed): the last frame's level and biomes, let go when the world is left. */
  public void forgetLevel() {
    previousLevel = null;
    previousBiomes = null;
  }
  private Vec3 cameraPosition = Vec3.ZERO;
  private Vec3 previousCameraPosition = Vec3.ZERO;
  private int previousWidth;
  private int previousHeight;
  private long previousNanos;
  private long startedNanos;
  private int frameCounter;
  private boolean historyReset = true;
  private boolean hasHistory;
  private boolean wasFrozen;
  private boolean hasCapturedProjection;
  private Registry<Biome> previousBiomes;
  private final Map<String, Double> biomeConstants = new HashMap<>();
  private float wetness;
  private float eyeBlockLight;
  private float eyeSkyLight;
  private float centerDepth = 1;
  private float centerDepthSmooth = 1;
  private float previousEndFlash;
  private CustomUniforms customUniforms;
  private ToIntFunction<ItemStack> itemIds = stack -> -1;

  public void setCustomUniforms(CustomUniforms expressions) {
    customUniforms = expressions;
    if (expressions != null) expressions.reset();
    hasHistory = false;
  }

  public void setItemIdResolver(ToIntFunction<ItemStack> resolver) {
    itemIds = Objects.requireNonNull(resolver);
  }

  /** Supply asynchronous depth readback when a pack requests autofocus; no synchronous GPU wait. */
  public void setCenterDepth(float depth) {
    if (!Float.isFinite(depth) || depth < 0 || depth > 1) {
      throw new IllegalArgumentException("Pack depth must be finite and in [0,1]");
    }
    centerDepth = depth;
  }

  public boolean historyReset() {
    return historyReset;
  }

  /** Read the exact values that write() will upload; camera assumptions alone are insufficient. */
  public boolean matchesMatrix(
      dev.cadence.shaders.compile.MatrixUniformSpecialization.ZeroPattern pattern) {
    return pattern.matches(matrices.get(pattern.uniform()));
  }

  public Vector3fc sunDirectionWorld() {
    return sunWorld;
  }

  /** The same world-space light used by shadowLightPosition and the shadow camera. */
  public Vector3fc shadowDirectionWorld() {
    return shadowWorld;
  }

  public Matrix4fc projection() {
    return projection;
  }

  public Matrix4fc modelView() {
    return modelView;
  }

  /** Vanilla terrain uniforms need the unmodified camera rotation; the adapter adds effects. */
  public Matrix4fc cameraViewRotation() {
    return cameraViewRotation;
  }

  public Matrix4fc shadowProjection() {
    return shadowProjection;
  }

  public Matrix4fc shadowModelView() {
    return shadowModelView;
  }

  public void beginFrame(Minecraft minecraft, int width, int height, ShaderDirectives globals)
      throws ShaderPackException {
    ClientLevel level = Objects.requireNonNull(minecraft.level, "Pack frame requires a level");
    var game = minecraft.gameRenderer.gameRenderState();
    var state = game.levelRenderState;
    CameraRenderState camera = state.cameraRenderState;
    float partialTicks = state.worldPartialTicks;
    long now = System.nanoTime();
    if (startedNanos == 0) startedNanos = now;
    // Cadence: a first frame lasts a frame (1/60 s), not 0: packs divide by frameTime (Sildur's TAA), and a NaN in a
    // history buffer that is never cleared stays forever (the whole picture black)
    float elapsed = previousNanos == 0 ? 1f / 60 : Math.max(1e-4f, (now - previousNanos) * 1.0e-9f);
    // Cadence (PackCheck): a frozen clock and a restartable frame counter, so two runs of the same view are identical
    if (wasFrozen && !dev.cadence.shaders.cadence.packcheck.PackCheck.frozen()) {
      // back from a pack check's frozen clock: start timing afresh (the frozen times were made up)
      wasFrozen = false;
      startedNanos = now;
      elapsed = 0;
      hasHistory = false;
    }
    if (dev.cadence.shaders.cadence.packcheck.PackCheck.frozen()) {
      wasFrozen = true;
      if (dev.cadence.shaders.cadence.packcheck.PackCheck.consumeSequenceStart()) {
        hasHistory = false;
        frameCounter = 0;
      }
      now = startedNanos + 100_000_000_000L + frameCounter * 16_666_667L;
      previousNanos = now - 16_666_667L;
      elapsed = 1f / 60f;
    }
    // Long loading stalls reset history instead of extrapolating previous-frame motion through
    // them.
    historyReset =
        !hasHistory
            || previousLevel != level
            // (not a resize: render-scale changes carry the pack's history over, and reset its smoothed uniforms here)
            || elapsed > 1
            || camera.pos.distanceToSqr(previousCameraPosition) > 64 * 64;
    previousNanos = now;
    previousLevel = level;
    previousWidth = width;
    previousHeight = height;
    cameraPosition = camera.pos;
    if (historyReset && customUniforms != null) customUniforms.reset();
    scalars.clear();
    vectors.clear();
    matrices.clear();
    updateCameraMatrices(
        camera.projectionMatrix,
        hasCapturedProjection ? capturedProjection : camera.projectionMatrix,
        camera.viewRotationMatrix);
    hasCapturedProjection = false;
    if (historyReset) {
      previousProjection.set(projection);
      previousModelView.set(modelView);
      previousCameraPosition = cameraPosition;
    }
    matrix("gbufferPreviousModelView", previousModelView);
    matrix("gbufferPreviousProjection", previousProjection);
    setShadowMatrices(shadowProjection, shadowModelView);
    put("frameTime", elapsed);
    put("frameTimeCounter", ((now - startedNanos) * 1.0e-9) % 3600);
    put("frameCounter", frameCounter);
    put("viewWidth", width);
    put("viewHeight", height);
    put("aspectRatio", (double) width / height);
    // The pack contract is terrain render distance, not the much farther cloud clipping plane.
    put("far", game.optionsRenderState.renderDistance * 16);
    put("cloudHeight", state.cloudHeight);
    put("screenBrightness", minecraft.options.gamma().get());
    put("worldTime", Math.floorMod(level.getOverworldClockTime(), 24000));
    put("worldDay", Math.floorDiv(level.getOverworldClockTime(), 24000));
    put("heightLimit", level.getMaxY() + 1);
    put("bedrockLevel", level.getMinY());
    put(
        "isEyeInWater",
        switch (camera.fogType) {
          case WATER -> 1;
          case LAVA -> 2;
          case POWDER_SNOW -> 3;
          default -> 0;
        });
    vector(
        "cameraPosition",
        (float) cameraPosition.x,
        (float) cameraPosition.y,
        (float) cameraPosition.z);
    vector(
        "cameraPositionFract",
        fract(cameraPosition.x),
        fract(cameraPosition.y),
        fract(cameraPosition.z));
    vector(
        "previousCameraPosition",
        (float) previousCameraPosition.x,
        (float) previousCameraPosition.y,
        (float) previousCameraPosition.z);
    vector("fogColor", camera.fogData.color.x, camera.fogData.color.y, camera.fogData.color.z);
    // Cadence: OpenGL's gl_Fog for older packs (Sildur's, Bliss): linear fog from the game's own start and end
    var fog = camera.fogData;
    // The game blends two linear fogs (the faint environmental one, 0..1024 blocks in clear air, and render distance's);
    // a pack's single linear fog is the one that becomes opaque first: render distance's in the open, the environmental
    // one underwater, in lava, blind. (Their union, 0..96, fogged everything for Sildur's Enhanced.)
    boolean environmental = fog.environmentalEnd < fog.renderDistanceEnd;
    float fogStart = environmental ? fog.environmentalStart : fog.renderDistanceStart;
    float fogEnd = Math.max(fogStart + 1e-3f, environmental ? fog.environmentalEnd : fog.renderDistanceEnd);
    vector("sl_Fog_color", fog.color.x, fog.color.y, fog.color.z, fog.color.w);
    put("sl_Fog_start", fogStart);
    put("sl_Fog_end", fogEnd);
    put("sl_Fog_scale", 1.0 / (fogEnd - fogStart));
    put("sl_Fog_density", 1.0);
    var probe = minecraft.gameRenderer.mainCamera().attributeProbe();
    vector("skyColor", probe.getValue(EnvironmentAttributes.SKY_COLOR, partialTicks));
    float skyAngle =
        (float) Math.toRadians(probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks));
    put("moonPhase", probe.getValue(EnvironmentAttributes.MOON_PHASE, partialTicks).index());
    float pathDegrees = (float) globals.floatConstant("sunPathRotation", 0);
    dev.cadence.metal.ApiHooks.sunPathRotation = pathDegrees; // (Cadence API, the Iris API's getSunPathRotation)
    float pathRotation = (float) Math.toRadians(pathDegrees);
    float moonAngle =
        (float) Math.toRadians(probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTicks));
    updateCelestial(
        skyAngle, moonAngle, pathRotation, level.dimension().equals(Level.END), modelView);
    vector("upPosition", modelView.transformDirection(new Vector3f(0, 1, 0)).normalize(100));
    put("endFlashIntensity", state.skyRenderState.endFlashIntensity);
    put(
        "previousEndFlashIntensity",
        historyReset ? state.skyRenderState.endFlashIntensity : previousEndFlash);
    Vector3f flash =
        new Matrix4f()
            .rotateY((float) Math.toRadians(180 - state.skyRenderState.endFlashYAngle))
            .rotateX((float) Math.toRadians(-90 - state.skyRenderState.endFlashXAngle))
            .transformDirection(new Vector3f(0, 100, 0));
    vector("endFlashPosition", modelView.transformDirection(flash).normalize(100));
    float rain = level.getRainLevel(partialTicks);
    float wetnessHalfLife =
        globals.floatConstant(
            rain > wetness ? "wetnessHalflife" : "drynessHalflife", rain > wetness ? 600 : 200);
    wetness = historyReset ? rain : smooth(wetness, rain, elapsed, wetnessHalfLife);
    put("rainStrength", rain);
    put("thunderStrength", level.getThunderLevel(partialTicks));
    put("wetness", wetness);
    float blockLight = level.getBrightness(LightLayer.BLOCK, camera.blockPos) * 16;
    float skyLight = level.getBrightness(LightLayer.SKY, camera.blockPos) * 16;
    float eyeHalfLife = globals.floatConstant("eyeBrightnessHalflife", 10);
    eyeBlockLight =
        historyReset ? blockLight : smooth(eyeBlockLight, blockLight, elapsed, eyeHalfLife);
    eyeSkyLight = historyReset ? skyLight : smooth(eyeSkyLight, skyLight, elapsed, eyeHalfLife);
    vector("eyeBrightness", blockLight, skyLight);
    vector("eyeBrightnessSmooth", Math.round(eyeBlockLight), Math.round(eyeSkyLight));
    centerDepthSmooth =
        historyReset
            ? centerDepth
            : smooth(
                centerDepthSmooth,
                centerDepth,
                elapsed,
                globals.floatConstant("centerDepthHalflife", 1));
    put("centerDepthSmooth", centerDepthSmooth);
    populateEntityStatus(minecraft, partialTicks);
    var atlas =
        minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
    vector("atlasSize", atlas.getWidth(0), atlas.getHeight(0));
    Registry<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
    var biomeHolder = level.getBiome(camera.blockPos);
    Biome biome = biomeHolder.value();
    put("biome", biomeId(biomes, biomeHolder.unwrapKey().orElse(null), biome));
    put("temperature", biome.getBaseTemperature());
    populateIrisExtras(minecraft, level, camera, biome, fogStart, fogEnd, partialTicks);
    if (previousBiomes != biomes) {
      biomeConstants.clear();
      for (var entry : biomes.entrySet()) {
        String key =
            entry.getKey().identifier().getPath().toUpperCase(Locale.ROOT).replace('/', '_');
        biomeConstants.put("BIOME_" + key, (double) biomeId(biomes, entry.getKey(), entry.getValue()));
      }
      previousBiomes = biomes;
    }
    scalars.putAll(biomeConstants);
    setDrawState(0, 0, -1, -1);
    vector("entityColor", 0, 0, 0, 0);
    if (customUniforms != null) {
      // Cadence: inputs written as a matrix element (MakeUp's gbufferProjection.1.1: column 1, row 1), and biome constants of
      // biomes this version no longer has (Sildur's 1.541 names BIOME_SWAMP_HILLS): -1, which no biome id equals
      for (String input : customUniforms.requiredInputs()) {
        if (scalars.containsKey(input)) continue;
        java.util.regex.Matcher element = MATRIX_ELEMENT.matcher(input);
        if (element.matches()) {
          float[] m = matrixValue(element.group(1), false, false);
          if (m == null) continue;
          int size = m.length == 9 ? 3 : 4, column = Integer.parseInt(element.group(2)), row = Integer.parseInt(element.group(3));
          if (column < size && row < size) scalars.put(input, (double) m[column * size + row]);
        } else if (input.startsWith("BIOME_")) scalars.put(input, -1.0);
      }
      Map<String, float[]> customVectors = new HashMap<>();
      scalars.putAll(customUniforms.evaluate(scalars, vectors, elapsed, customVectors));
      vectors.putAll(customVectors);
    }
  }

  /**
   * Public pack uniforms require shadowLightPosition to match the shadow pass direction. The End
   * has no celestial timeline in 26.3. Our initial BSL compatibility follows its modern non-Iris
   * fixed half-turn light convention there, including light below the horizon. Keep the End's
   * environment cycle angle unchanged: BSL uses it separately to select the light's sign.
   * https://shaders.properties/current/reference/uniforms/world/#shadowlightposition
   */
  void updateCelestial(
      float skyAngle, float moonAngle, float pathRotation, boolean end, Matrix4fc view) {
    float sunAngle = fract(skyAngle / (Math.PI * 2) + 0.25);
    put("sunAngle", sunAngle);
    put("shadowAngle", sunAngle > 0.5 ? sunAngle - 0.5 : sunAngle);
    celestialDirection(end ? (float) Math.PI : skyAngle, pathRotation, sunWorld);
    Vector3f moonWorld =
        end
            ? new Vector3f(sunWorld).negate()
            : celestialDirection(moonAngle, pathRotation, new Vector3f());
    shadowWorld.set(!end && sunAngle > 0.5 ? moonWorld : sunWorld);
    vector("sunPosition", view.transformDirection(new Vector3f(sunWorld)).normalize(100));
    vector("moonPosition", view.transformDirection(moonWorld).normalize(100));
    vector(
        "shadowLightPosition", view.transformDirection(new Vector3f(shadowWorld)).normalize(100));
  }

  private static Vector3f celestialDirection(float angle, float pathRotation, Vector3f target) {
    return target.set(
        -(float) Math.sin(angle),
        (float) (Math.cos(angle) * Math.cos(pathRotation)),
        (float) (-Math.cos(angle) * Math.sin(pathRotation)));
  }

  private void populateEntityStatus(Minecraft minecraft, float partialTicks) {
    var entity = minecraft.gameRenderer.mainCamera().entity();
    LivingEntity living = entity instanceof LivingEntity value ? value : null;
    var player = minecraft.player;
    Vec3 eye = entity == null ? cameraPosition : entity.getEyePosition(partialTicks);
    vector(
        "relativeEyePosition",
        (float) (cameraPosition.x - eye.x),
        (float) (cameraPosition.y - eye.y),
        (float) (cameraPosition.z - eye.z));
    float blindness = 0;
    if (living != null && living.getEffect(MobEffects.BLINDNESS) != null) {
      int duration = living.getEffect(MobEffects.BLINDNESS).getDuration();
      blindness = duration < 0 ? 1 : Math.clamp(duration / 20.0f, 0, 1);
    }
    put("blindness", blindness);
    put(
        "darknessFactor",
        living == null ? 0 : living.getEffectBlendFactor(MobEffects.DARKNESS, partialTicks));
    var lightmap = minecraft.gameRenderer.gameRenderState().lightmapRenderState;
    put("darknessLightFactor", lightmap.darknessEffectScale);
    put("nightVision", lightmap.nightVisionEffectIntensity);
    put("isRightHanded", player == null || player.getMainArm() == HumanoidArm.RIGHT ? 1 : 0);
    ItemStack first = player == null ? ItemStack.EMPTY : player.getMainHandItem();
    ItemStack second = player == null ? ItemStack.EMPTY : player.getOffhandItem();
    put("heldItemId", first.isEmpty() ? -1 : itemIds.applyAsInt(first));
    put("heldItemId2", second.isEmpty() ? -1 : itemIds.applyAsInt(second));
    put("heldBlockLightValue", heldBlockLight(first));
    put("heldBlockLightValue2", heldBlockLight(second));
  }

  /**
   * Cadence: the rest of the uniforms Iris and OptiFine document (shaders.properties reference: player status, biome and
   * dimension, fog, date and time, camera parts) and the constants custom uniform expressions compare them with (CAT_*,
   * PPT_*), so packs written for Iris get real values instead of zero.
   */
  private void populateIrisExtras(
      Minecraft minecraft, ClientLevel level, CameraRenderState camera, Biome biome, float fogStart, float fogEnd,
      float partialTicks) {
    var entity = minecraft.gameRenderer.mainCamera().entity();
    LivingEntity living = entity instanceof LivingEntity value ? value : null;
    var player = minecraft.player;
    // fog: linear, the game's own start and end (fogMode GL_LINEAR; shape 1 cylinder for distance fog, 0 sphere in fluids)
    var fog = camera.fogData;
    put("fogStart", fogStart);
    put("fogEnd", fogEnd);
    put("fogDensity", 1.0);
    put("fogMode", 9729);
    put("fogShape", fog.environmentalEnd < fog.renderDistanceEnd ? 0 : 1);
    // camera position in integer and fractional parts
    vector("cameraPositionInt", (float) Math.floor(cameraPosition.x), (float) Math.floor(cameraPosition.y),
        (float) Math.floor(cameraPosition.z));
    vector("previousCameraPositionInt", (float) Math.floor(previousCameraPosition.x),
        (float) Math.floor(previousCameraPosition.y), (float) Math.floor(previousCameraPosition.z));
    vector("previousCameraPositionFract", fract(previousCameraPosition.x), fract(previousCameraPosition.y),
        fract(previousCameraPosition.z));
    put("eyeAltitude", cameraPosition.y);
    Vec3 eye = entity == null ? cameraPosition : entity.getEyePosition(partialTicks);
    vector("eyePosition", (float) eye.x, (float) eye.y, (float) eye.z);
    Vec3 look = entity == null ? new Vec3(0, 0, 1) : entity.getViewVector(partialTicks);
    vector("playerLookVector", (float) look.x, (float) look.y, (float) look.z);
    Vec3 body = living == null ? look : Vec3.directionFromRotation(0, living.yBodyRot);
    vector("playerBodyVector", (float) body.x, (float) body.y, (float) body.z);
    put("firstPersonCamera", minecraft.options.getCameraType().isFirstPerson() ? 1 : 0);
    put("hideGUI", minecraft.gui.hud.isHidden() ? 1 : 0);
    // player status (-1 where there's no player, as Iris)
    put("isSpectator", player != null && player.isSpectator() ? 1 : 0);
    put("playerMood", player == null ? 0 : player.getCurrentMood());
    put("constantMood", player == null ? 0 : player.getCurrentMood());
    put("currentPlayerHealth", living == null ? -1 : living.getHealth() / Math.max(1e-3f, living.getMaxHealth()));
    put("maxPlayerHealth", living == null ? -1 : living.getMaxHealth());
    put("currentPlayerAir", entity == null ? -1 : (double) entity.getAirSupply() / Math.max(1, entity.getMaxAirSupply()));
    put("maxPlayerAir", entity == null ? -1 : entity.getMaxAirSupply());
    put("currentPlayerArmor", living == null ? -1 : living.getArmorValue() / 50.0);
    put("maxPlayerArmor", 50);
    put("currentPlayerHunger", player == null ? -1 : player.getFoodData().getFoodLevel() / 20.0);
    put("maxPlayerHunger", 20);
    put("isRiding", entity != null && entity.isPassenger() ? 1 : 0);
    put("vehicleInWater", entity != null && entity.getVehicle() != null && entity.getVehicle().isInWater() ? 1 : 0);
    put("inSwimmingAnimation", entity != null && entity.isVisuallySwimming() ? 1 : 0);
    put("feetInWater", entity != null && entity.isInWater() ? 1 : 0);
    put("isElytraFlying", living != null && living.isFallFlying() ? 1 : 0);
    // OptiFine's custom-uniform entity variables (the camera entity)
    put("is_alive", entity != null && entity.isAlive() ? 1 : 0);
    put("is_burning", entity != null && entity.isOnFire() ? 1 : 0);
    put("is_child", living != null && living.isBaby() ? 1 : 0);
    put("is_glowing", entity != null && entity.isCurrentlyGlowing() ? 1 : 0);
    put("is_hurt", living != null && living.hurtTime > 0 ? 1 : 0);
    put("is_in_lava", entity != null && entity.isInLava() ? 1 : 0);
    put("is_in_water", entity != null && entity.isInWater() ? 1 : 0);
    put("is_invisible", entity != null && entity.isInvisible() ? 1 : 0);
    put("is_on_ground", entity != null && entity.onGround() ? 1 : 0);
    put("is_ridden", entity != null && entity.isVehicle() ? 1 : 0);
    put("is_riding", entity != null && entity.isPassenger() ? 1 : 0);
    put("is_sneaking", entity != null && entity.isCrouching() ? 1 : 0);
    put("is_sprinting", entity != null && entity.isSprinting() ? 1 : 0);
    put("is_wet", entity != null && entity.isInWaterOrRain() ? 1 : 0);
    // biome and dimension
    var precipitation = biome.getPrecipitationAt(camera.blockPos, level.getSeaLevel());
    put("biome_precipitation", switch (precipitation) {
      case NONE -> 0;
      case RAIN -> 1;
      case SNOW -> 2;
    });
    put("PPT_NONE", 0);
    put("PPT_RAIN", 1);
    put("PPT_SNOW", 2);
    put("rainfall", downfall(biome));
    var holder = level.getBiome(camera.blockPos);
    put("biome_category", biomeCategory(holder, camera.blockPos.getY() < level.getSeaLevel() - 10));
    for (int i = 0; i < CATEGORIES.length; i++) put("CAT_" + CATEGORIES[i], i);
    var dimension = level.dimensionType();
    put("hasCeiling", dimension.hasCeiling() ? 1 : 0);
    put("hasSkylight", dimension.hasSkyLight() ? 1 : 0);
    put("ambientLight", dimension.ambientLight());
    put("logicalHeightLimit", dimension.logicalHeight());
    // the computer's date and time
    var now = java.time.LocalDateTime.now();
    vector("currentDate", now.getYear(), now.getMonthValue(), now.getDayOfMonth());
    vector("currentTime", now.getHour(), now.getMinute(), now.getSecond());
    int secondOfYear = (now.getDayOfYear() - 1) * 86400 + now.toLocalTime().toSecondOfDay();
    vector("currentYearTime", secondOfYear, now.toLocalDate().lengthOfYear() * 86400 - secondOfYear);
    put("currentColorSpace", 0); // sRGB
    put("textureFilteringMode", 0);
    vector("lightningBoltPosition", 0, 0, 0, 0);
    vector("blendFunc", 0, 0, 0, 0);
  }

  private static final java.util.Map<Biome, Float> DOWNFALL = new java.util.WeakHashMap<>();

  /**
   * A biome's downfall (the "rainfall" uniform). The climate settings are private to Biome; the game's own network codec
   * writes them out, once per biome. 0.5 (plains) if that fails.
   */
  private static float downfall(Biome biome) {
    return DOWNFALL.computeIfAbsent(biome, b -> {
      try {
        var json = Biome.NETWORK_CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, b).getOrThrow();
        return json.getAsJsonObject().get("downfall").getAsFloat();
      } catch (RuntimeException e) {
        return 0.5f;
      }
    });
  }

  /**
   * Cadence: a biome's number as Iris gives it: the vanilla biomes in the order net.minecraft.world.level.biome.Biomes
   * declares them (packs write these numbers into their custom uniforms: Bliss's isSwamps = in(biome, 6, 52, 7)), other
   * biomes after them. The client registry's own ids follow its data order instead: the test world's cold ocean was 6
   * there, and Bliss filled its sky with swamp fog.
   */
  private static java.util.Map<net.minecraft.resources.ResourceKey<Biome>, Integer> vanillaBiomeIds;

  @SuppressWarnings("unchecked")
  static int biomeId(Registry<Biome> biomes, net.minecraft.resources.ResourceKey<Biome> key, Biome biome) {
    if (vanillaBiomeIds == null) {
      var ids = new java.util.HashMap<net.minecraft.resources.ResourceKey<Biome>, Integer>();
      for (var field : net.minecraft.world.level.biome.Biomes.class.getDeclaredFields()) {
        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
            || field.getType() != net.minecraft.resources.ResourceKey.class) continue;
        try {
          field.setAccessible(true);
          ids.putIfAbsent((net.minecraft.resources.ResourceKey<Biome>) field.get(null), ids.size());
        } catch (ReflectiveOperationException | RuntimeException e) {
          // (an unreadable field: that biome falls back to the registry order below)
        }
      }
      vanillaBiomeIds = java.util.Map.copyOf(ids);
    }
    Integer id = key == null ? null : vanillaBiomeIds.get(key);
    return id != null ? id : vanillaBiomeIds.size() + biomes.getId(biome);
  }

  /** Iris's biome categories (OptiFine's order), the values of CAT_* and biome_category. */
  static final String[] CATEGORIES = {
    "NONE", "TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS", "SAVANNA", "ICY", "THE_END", "BEACH", "FOREST", "OCEAN",
    "DESERT", "RIVER", "SWAMP", "MUSHROOM", "NETHER", "MOUNTAIN", "UNDERGROUND"
  };

  private static int biomeCategory(net.minecraft.core.Holder<Biome> biome, boolean underground) {
    String path = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
    String name =
        biome.is(net.minecraft.tags.BiomeTags.IS_NETHER) ? "NETHER"
            : biome.is(net.minecraft.tags.BiomeTags.IS_END) ? "THE_END"
            : path.contains("mushroom") ? "MUSHROOM"
            : path.contains("swamp") ? "SWAMP"
            : biome.is(net.minecraft.tags.BiomeTags.IS_RIVER) ? "RIVER"
            : biome.is(net.minecraft.tags.BiomeTags.IS_BEACH) ? "BEACH"
            : biome.is(net.minecraft.tags.BiomeTags.IS_OCEAN) ? "OCEAN"
            : biome.is(net.minecraft.tags.BiomeTags.IS_BADLANDS) ? "MESA"
            : path.contains("desert") ? "DESERT"
            : biome.is(net.minecraft.tags.BiomeTags.IS_JUNGLE) ? "JUNGLE"
            : biome.is(net.minecraft.tags.BiomeTags.IS_SAVANNA) ? "SAVANNA"
            : biome.is(net.minecraft.tags.BiomeTags.IS_TAIGA) ? "TAIGA"
            : path.contains("frozen") || path.contains("ice") || path.contains("snowy") ? "ICY"
            : biome.is(net.minecraft.tags.BiomeTags.IS_MOUNTAIN) ? "MOUNTAIN"
            : biome.is(net.minecraft.tags.BiomeTags.IS_HILL) ? "EXTREME_HILLS"
            : biome.is(net.minecraft.tags.BiomeTags.IS_FOREST) ? "FOREST"
            : path.contains("cave") || path.contains("deep_dark") || underground && path.contains("dripstone") ? "UNDERGROUND"
            : path.contains("plains") || path.contains("meadow") ? "PLAINS"
            : "NONE";
    return java.util.Arrays.asList(CATEGORIES).indexOf(name);
  }

  private static int heldBlockLight(ItemStack stack) {
    return stack.getItem() instanceof BlockItem block
        ? block.getBlock().defaultBlockState().getLightEmission()
        : 0;
  }

  /** Capture the final native camera projection after view bob/nausea before drawing the level. */
  public void captureProjection(Matrix4fc nativeProjection) {
    capturedProjection.set(nativeProjection);
    hasCapturedProjection = true;
  }

  /**
   * Minecraft 26.3 places bob, hurt tilt and nausea in P*B. Legacy packs reconstruct depth using
   * the diagonal of P inverse, so retain the symmetric perspective P and move B into model-view.
   * Rasterization remains P*B*V; its inverse is now also valid for those optimized pack helpers.
   */
  void updateCameraMatrices(
      Matrix4fc baseNativeProjection, Matrix4fc combinedNativeProjection, Matrix4fc viewRotation) {
    cameraViewRotation.set(viewRotation);
    cameraEffect.set(baseNativeProjection).invert().mul(combinedNativeProjection);
    // Every vanilla camera effect is affine. Remove numerical residue from perspective inversion.
    cameraEffect.m03(0).m13(0).m23(0).m33(1);
    modelView.set(cameraEffect).mul(cameraViewRotation);
    matrix("sl_CameraEffect", cameraEffect);
    matrix("sl_CameraEffectInverse", new Matrix4f(cameraEffect).invert());
    matrix("gbufferModelView", modelView);
    matrix("gbufferModelViewInverse", new Matrix4f(modelView).invert());
    setProjection(baseNativeProjection);
  }

  /** Updates the base perspective; use captureProjection to supply the combined native P*B. */
  public void setProjection(Matrix4fc nativeProjection) {
    projection.identity().m22(-2).m32(1).mul(nativeProjection);
    matrix("gbufferProjection", projection);
    matrix("gbufferProjectionInverse", new Matrix4f(projection).invert());
    matrix("sl_TerrainModelViewProjection", new Matrix4f(projection).mul(modelView));
    if (historyReset) {
      previousProjection.set(projection);
      matrix("gbufferPreviousProjection", previousProjection);
    }
    float denominator = 1 + nativeProjection.m22();
    put("near", Math.abs(denominator) > 1.0e-8f ? nativeProjection.m32() / denominator : 0.05);
    setDistantProjection();
  }

  /**
   * Cadence's far terrain for packs with dh_* programs (Iris's Distant Horizons uniforms): the camera's projection with a
   * finite near and far for the far terrain's own depth (dhDepthTex0/1): near 16 blocks, far 1.5 x its reach; the far
   * terrain's model-view is the camera's.
   */
  private final Matrix4f dhProjection = new Matrix4f(), dhPrevious = new Matrix4f();
  private boolean dhPreviousSet;

  private void setDistantProjection() {
    double reach = dev.cadence.shaders.cadence.render.DistantTerrain.reachBlocks();
    // (no far terrain in this frame, the Nether or before it exists: the render distance stands in. Zeros made packs that
    // divide by them draw black (Photon's combined depth) or nothing but fog (superDuperVanilla))
    if (reach <= 0) reach = Math.max(32, net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16);
    float n = 16f, f = (float) (reach * 1.5);
    if (dhPreviousSet) dhPrevious.set(dhProjection);
    dhProjection.set(projection).m22(-(f + n) / (f - n)).m32(-2f * f * n / (f - n));
    if (!dhPreviousSet) {
      dhPrevious.set(dhProjection);
      dhPreviousSet = true;
    }
    matrix("dhProjection", dhProjection);
    matrix("dhProjectionInverse", new Matrix4f(dhProjection).invert());
    matrix("dhPreviousProjection", dhPrevious);
    matrix("dhModelView", modelView);
    matrix("dhModelViewInverse", new Matrix4f(modelView).invert());
    matrix("dhPreviousModelView", historyReset ? modelView : previousModelView);
    put("dhNearPlane", n);
    put("dhFarPlane", f);
    put("dhRenderDistance", reach);
  }

  /** The supplied projection is conventional OpenGL -1..1 depth; both matrices are copied. */
  public void setShadowMatrices(Matrix4fc packProjection, Matrix4fc packModelView) {
    shadowProjection.set(packProjection);
    shadowModelView.set(packModelView);
    matrix("shadowProjection", shadowProjection);
    matrix("shadowProjectionInverse", new Matrix4f(shadowProjection).invert());
    matrix("shadowModelView", shadowModelView);
    matrix("shadowModelViewInverse", new Matrix4f(shadowModelView).invert());
    matrix("sl_ShadowModelViewProjection", new Matrix4f(shadowProjection).mul(shadowModelView));
  }

  private ToIntFunction<String> trimIds = material -> -1;

  /** Cadence: the pack id of an armour trim material (item.properties trim_&lt;material&gt;), -1 if it has none. */
  public int trimId(String material) {
    return trimIds.applyAsInt(material);
  }

  public void setTrimIdResolver(ToIntFunction<String> resolver) {
    trimIds = Objects.requireNonNull(resolver);
  }

  /** Cadence: an item's pack id (item.properties), -1 if it has none. */
  public int itemId(ItemStack stack) {
    return itemIds.applyAsInt(stack);
  }

  public void setDrawState(int stage, float alphaTestRef, int entityId, int blockEntityId) {
    setDrawState(stage, alphaTestRef, entityId, blockEntityId, 0);
  }

  public void setDrawState(int stage, float alphaTestRef, int entityId, int blockEntityId, int itemId) {
    put("currentRenderedItemId", itemId);
    put("renderStage", stage);
    put("alphaTestRef", alphaTestRef);
    put("entityId", entityId);
    put("blockEntityId", blockEntityId);
  }

  public void setEntityColor(float red, float green, float blue, float alpha) {
    vector("entityColor", red, green, blue, alpha);
  }

  public void write(UniformLayout layout, ByteBuffer target, boolean fullscreen, boolean shadow) {
    target.order(ByteOrder.nativeOrder());
    for (int offset = 0; offset < layout.byteSize(); offset += 8) target.putLong(offset, 0);
    for (UniformLayout.Field field : layout.fields()) {
      String name = field.name();
      if (field.type().startsWith("mat")) {
        float[] data = matrixValue(name, fullscreen, shadow);
        if (data == null) {
          missing(layout, field, target, "matrix");
          continue;
        }
        writeComponents(target, field, data, false);
      } else if (field.type().contains("vec")) {
        float[] data = vectors.get(name);
        if (data == null) {
          missing(layout, field, target, "vector");
          continue;
        }
        writeComponents(target, field, data, !field.type().startsWith("vec"));
      } else {
        Double value = scalars.get(name);
        if (value == null) {
          missing(layout, field, target, "value");
          continue;
        }
        if (field.arrayLength() > 0)
          throw new IllegalStateException("No array supplied for " + name);
        if (field.type().equals("float")) {
          if (IRIS_INT_SCALARS.contains(name) && (customUniforms == null || !customUniforms.definesUniform(name))) {
            wrongType(layout, field, target);
            continue;
          }
          target.putFloat(field.offset(), value.floatValue());
        } else target.putInt(field.offset(), value.intValue());
      }
    }
    target.position(0).limit(layout.byteSize());
  }

  /**
   * Cadence: the uniforms Iris supplies as int. A pack declaring one float (Pegasus: {@code uniform float frameCounter})
   * gets nothing from Iris ("Wrong uniform type ... Disabling that uniform") or OptiFine (glUniform1i on a float is an
   * error): it reads its initializer or 0, and the pack was tuned that way. Converting the value instead animated Pegasus's
   * noise every frame (frameCounter as a large float). -Dcadence.shaders.convertWrongTypes=true converts as before.
   */
  private static final java.util.Set<String> IRIS_INT_SCALARS = Boolean.getBoolean("cadence.shaders.convertWrongTypes")
      ? java.util.Set.of()
      : java.util.Set.of("frameCounter", "worldTime", "worldDay", "moonPhase", "isEyeInWater", "heldItemId", "heldItemId2",
          "heldBlockLightValue", "heldBlockLightValue2", "fogMode", "fogShape", "hideGUI", "biome", "biome_category",
          "biome_precipitation", "heightLimit", "bedrockLevel", "logicalHeightLimit", "renderStage", "entityId",
          "blockEntityId", "currentColorSpace", "currentRenderedItemId");
  private static final java.util.Set<String> REPORTED_WRONG_TYPE = java.util.concurrent.ConcurrentHashMap.newKeySet();

  private void wrongType(UniformLayout layout, UniformLayout.Field field, ByteBuffer target) {
    float[] initial = layout.defaults().get(field.name());
    if (initial != null && field.arrayLength() == 0) target.putFloat(field.offset(), initial[0]);
    if (REPORTED_WRONG_TYPE.add(field.name()))
      LoggerFactory.getLogger("cadence_shaders")
          .info("Pack uniform {} is declared float but is an int uniform: left at {}, as Iris and OptiFine leave it",
              field.name(), initial != null ? "the pack's initial value" : "zero");
  }

  /**
   * Cadence: -Dcadence.shaders.strictUniforms=true refuses a pack uniform the game doesn't supply (the loader's original rule).
   * Otherwise it gets what OpenGL gives a uniform nobody sets, as OptiFine and Iris leave it: the pack's own initializer, or
   * zero (the buffer was zeroed). Each name is logged once.
   */
  private static final java.util.regex.Pattern MATRIX_ELEMENT =
      java.util.regex.Pattern.compile("(\\w+)\\.([0-3])\\.([0-3])");
  private static final boolean STRICT_UNIFORMS = Boolean.getBoolean("cadence.shaders.strictUniforms");
  private static final java.util.Set<String> REPORTED_MISSING = java.util.concurrent.ConcurrentHashMap.newKeySet();

  private void missing(UniformLayout layout, UniformLayout.Field field, ByteBuffer target, String kind) {
    if (STRICT_UNIFORMS)
      throw new IllegalStateException("No game " + kind + " supplied for pack uniform " + field.name());
    float[] initial = layout.defaults().get(field.name());
    if (initial != null && field.arrayLength() == 0) {
      if (field.type().contains("vec")) writeComponents(target, field, initial, !field.type().startsWith("vec"));
      else if (field.type().equals("float")) target.putFloat(field.offset(), initial[0]);
      else target.putInt(field.offset(), Math.round(initial[0]));
    }
    if (REPORTED_MISSING.add(field.name()))
      LoggerFactory.getLogger("cadence_shaders")
          .info("Pack uniform {} ({}) isn't supplied by Cadence yet: using {}", field.name(), field.type(),
              initial != null ? "the pack's initial value" : "zero");
  }

  /** The names of pack uniforms that weren't supplied (for the census and logs). */
  public static java.util.Set<String> missingUniforms() {
    return java.util.Set.copyOf(REPORTED_MISSING);
  }

  /** Use the already resolved field; searching the whole layout for each field is quadratic. */
  private static void writeComponents(
      ByteBuffer target, UniformLayout.Field field, float[] values, boolean integral) {
    String type = field.type();
    int rows = type.charAt(type.length() - 1) - '0';
    int components = field.matrixStride() == 0 ? rows : rows * (type.charAt(3) - '0');
    int elements = Math.max(field.arrayLength(), 1);
    if (values.length != components * elements) {
      throw new IllegalStateException(
          "Wrong number of components for pack uniform " + field.name());
    }
    for (int element = 0; element < elements; element++) {
      int start = field.offset() + element * field.arrayStride();
      for (int component = 0; component < components; component++) {
        int offset =
            start
                + (field.matrixStride() == 0
                    ? component * 4
                    : component / rows * field.matrixStride() + component % rows * 4);
        float value = values[element * components + component];
        if (integral) target.putInt(offset, Math.round(value));
        else target.putFloat(offset, value);
      }
    }
  }

  private float[] matrixValue(String name, boolean fullscreen, boolean shadow) {
    Matrix4f view = shadow ? shadowModelView : modelView;
    Matrix4f proj = shadow ? shadowProjection : projection;
    return switch (name) {
      case "sl_ModelViewMatrix" -> fullscreen ? IDENTITY : view.get(new float[16]);
      case "sl_ProjectionMatrix" -> fullscreen ? FULLSCREEN_ORTHO : proj.get(new float[16]);
      case "sl_ModelViewProjectionMatrix" ->
          fullscreen ? FULLSCREEN_ORTHO : new Matrix4f(proj).mul(view).get(new float[16]);
      case "sl_NormalMatrix" ->
          fullscreen ? IDENTITY3 : new Matrix3f(view).invert().transpose().get(new float[9]);
      case "sl_TextureMatrix" -> TEXTURE_MATRICES;
      default -> matrices.get(name);
    };
  }

  public void endFrame() {
    previousProjection.set(projection);
    previousModelView.set(modelView);
    previousCameraPosition = cameraPosition;
    previousEndFlash = scalars.getOrDefault("endFlashIntensity", 0.0).floatValue();
    frameCounter = (frameCounter + 1) & 0x7fffffff;
    hasHistory = true;
  }

  private void put(String name, double value) {
    scalars.put(name, value);
  }

  private void vector(String name, Vector3fc value) {
    vector(name, value.x(), value.y(), value.z());
  }

  // Cadence: "name.x" .. "name.w", built once per uniform name (were four new strings per vector per frame)
  private final Map<String, String[]> componentNames = new HashMap<>();

  private void vector(String name, float... values) {
    vectors.put(name, values);
    String[] names = componentNames.computeIfAbsent(
        name, n -> new String[] {n + ".x", n + ".y", n + ".z", n + ".w"});
    for (int i = 0; i < values.length; i++) put(names[i], values[i]);
  }

  private void matrix(String name, Matrix4fc value) {
    matrices.put(name, value.get(new float[16]));
  }

  private static float fract(double value) {
    return (float) (value - Math.floor(value));
  }

  private static float smooth(float before, float target, float seconds, float halfLifeTicks) {
    if (halfLifeTicks <= 0) return target;
    return target + (before - target) * (float) Math.pow(0.5, seconds * 20 / halfLifeTicks);
  }

  private static float[] textureMatrices() {
    float[] result = new float[8 * 16];
    for (int index = 0; index < 8; index++) System.arraycopy(IDENTITY, 0, result, index * 16, 16);
    result[16] = 1.0f / 256;
    result[21] = 1.0f / 256;
    result[28] = 8.0f / 256;
    result[29] = 8.0f / 256;
    return result;
  }
}
