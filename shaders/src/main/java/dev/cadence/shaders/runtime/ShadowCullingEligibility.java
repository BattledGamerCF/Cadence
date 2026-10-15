// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import dev.cadence.shaders.pack.ShaderPack;
import dev.cadence.shaders.pack.ShaderPackException;
import java.util.Map;

/** The receiver bounds are an audited pack contract, not a capability inferred from a name. */
public final class ShadowCullingEligibility {
  static final String BSL_10_1_8_FINGERPRINT =
      "fb4652d2b9bf7a142e56f6cad4046bd18115e2b5d4d61f534b2e711c309f925c";

  // Deliberately pinned independently of PackEnvironment: a new advertised capability or stage
  // changes preprocessing and must receive another source/receiver audit before enabling culling.
  private static final Map<String, String> AUDITED_ENVIRONMENT =
      Map.ofEntries(
          Map.entry("MC_VERSION", "260300"),
          Map.entry("MC_GL_VERSION", "330"),
          Map.entry("MC_GLSL_VERSION", "330"),
          Map.entry("MC_OS_MAC", ""),
          Map.entry("MC_HAND_DEPTH", "0.125"),
          Map.entry("MC_RENDER_STAGE_NONE", "0"),
          Map.entry("MC_RENDER_STAGE_SKY", "1"),
          Map.entry("MC_RENDER_STAGE_SUNSET", "2"),
          Map.entry("MC_RENDER_STAGE_CUSTOM_SKY", "3"),
          Map.entry("MC_RENDER_STAGE_SUN", "4"),
          Map.entry("MC_RENDER_STAGE_MOON", "5"),
          Map.entry("MC_RENDER_STAGE_STARS", "6"),
          Map.entry("MC_RENDER_STAGE_VOID", "7"),
          Map.entry("MC_RENDER_STAGE_TERRAIN_SOLID", "8"),
          Map.entry("MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED", "9"),
          Map.entry("MC_RENDER_STAGE_TERRAIN_CUTOUT", "10"),
          Map.entry("MC_RENDER_STAGE_ENTITIES", "11"),
          Map.entry("MC_RENDER_STAGE_BLOCK_ENTITIES", "12"),
          Map.entry("MC_RENDER_STAGE_DESTROY", "13"),
          Map.entry("MC_RENDER_STAGE_OUTLINE", "14"),
          Map.entry("MC_RENDER_STAGE_DEBUG", "15"),
          Map.entry("MC_RENDER_STAGE_HAND_SOLID", "16"),
          Map.entry("MC_RENDER_STAGE_TERRAIN_TRANSLUCENT", "17"),
          Map.entry("MC_RENDER_STAGE_TRIPWIRE", "18"),
          Map.entry("MC_RENDER_STAGE_PARTICLES", "19"),
          Map.entry("MC_RENDER_STAGE_CLOUDS", "20"),
          Map.entry("MC_RENDER_STAGE_RAIN_SNOW", "21"),
          Map.entry("MC_RENDER_STAGE_WORLD_BORDER", "22"),
          Map.entry("MC_RENDER_STAGE_HAND_TRANSLUCENT", "23"));

  public record Result(boolean eligible, String reason) {}

  private ShadowCullingEligibility() {}

  /** Evaluate once for a pack graph, using the exact world dimension before folder resolution. */
  public static Result assess(
      ShaderPack pack,
      Map<String, String> options,
      String dimension,
      Map<String, String> environment)
      throws ShaderPackException {
    return evaluate(
        pack.contentFingerprint(),
        pack.optionValues(options),
        pack.optionValues(Map.of()),
        dimension,
        pack.dimensionFolder(dimension),
        environment);
  }

  static Result evaluate(
      String fingerprint,
      Map<String, String> effectiveOptions,
      Map<String, String> defaults,
      String dimension,
      String directory,
      Map<String, String> environment) {
    if (!BSL_10_1_8_FINGERPRINT.equals(fingerprint))
      return new Result(false, "unrecognized-pack-content");
    String preset = "";
    if (!effectiveOptions.equals(defaults)) {
      String why = presetDifference(effectiveOptions, defaults);
      if (why != null) return new Result(false, "non-default-pack-options (" + why + ")");
      preset = "-preset";
    }
    if (!"minecraft:overworld".equals(dimension)) return new Result(false, "unsupported-dimension");
    if (!"world0".equals(directory)) return new Result(false, "unsupported-pack-directory");
    if (!AUDITED_ENVIRONMENT.equals(environment)) return new Result(false, "unsupported-environment");
    return new Result(true, "audited-bsl-10.1.8-defaults-overworld-v1" + preset);
  }

  /**
   * Cadence: BSL's quality presets (profile.MINIMUM..ULTRA) only switch shadow-related features off (LIGHT_SHAFT,
   * SHADOW_COLOR, SHADOW_FILTER, TAA; SHADOW itself, which leaves nothing to cull), change AO (screen space, no shadow
   * lookups), and scale shadowMapResolution with shadowDistance. With those features off, every shadow lookup is one the
   * defaults also make; the filter's reach in blocks scales with the shadow texel (2 * distance / resolution): 0.25 block
   * at the defaults and at LOW/MEDIUM/HIGH, 1/3 at ULTRA, i.e. at most 4/3 of the audited reach (13.9 -> 18.5 blocks;
   * with raster, waving and bias still far inside the 64-block padding). Returns null when the options differ from the
   * defaults only in those ways, else what differs.
   */
  static String presetDifference(Map<String, String> options, Map<String, String> defaults) {
    java.util.Set<String> offOnly = java.util.Set.of("LIGHT_SHAFT", "SHADOW_COLOR", "SHADOW_FILTER", "TAA", "SHADOW");
    java.util.Set<String> free = java.util.Set.of("AO", "shadowMapResolution", "shadowDistance");
    java.util.Set<String> keys = new java.util.HashSet<>(options.keySet());
    keys.addAll(defaults.keySet());
    for (String key : keys) {
      String value = options.get(key), base = defaults.get(key);
      if (java.util.Objects.equals(value, base)) continue;
      if (free.contains(key)) continue;
      if (offOnly.contains(key) && "false".equals(value)) continue;
      return key + "=" + value;
    }
    try {
      double distance = Double.parseDouble(options.getOrDefault("shadowDistance", defaults.get("shadowDistance")));
      double resolution = Double.parseDouble(options.getOrDefault("shadowMapResolution", defaults.get("shadowMapResolution")));
      if (!(resolution > 0) || 2 * distance / resolution > 1.0 / 3 + 1e-9) return "shadow texel above 1/3 block";
    } catch (RuntimeException e) {
      return "shadow size unknown";
    }
    return null;
  }
}
