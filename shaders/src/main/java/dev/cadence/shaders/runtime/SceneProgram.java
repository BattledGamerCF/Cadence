// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;

/** Selects the pack's scene category while retaining Minecraft's own batching and draw order. */
public record SceneProgram(String name, int stage) {
  public static SceneProgram select(RenderPipeline pipeline, boolean hand, boolean shadow) {
    return select(pipeline, hand, shadow, false);
  }

  public static SceneProgram select(
      RenderPipeline pipeline, boolean hand, boolean shadow, boolean blockEntity) {
    String path = pipeline.getLocation().getPath();
    boolean standaloneGlint =
        pipeline
            .getShaders()
            .get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX)
            .getPath()
            .equals("core/glint");
    String stage;
    String name;
    if (path.contains("terrain") || path.contains("wireframe")) {
      boolean translucent = path.contains("translucent");
      name = translucent ? "gbuffers_water" : "gbuffers_terrain";
      stage =
          translucent
              ? "TERRAIN_TRANSLUCENT"
              : path.contains("cutout") ? "TERRAIN_CUTOUT" : "TERRAIN_SOLID";
    } else if (path.endsWith("/stars")) {
      name = "gbuffers_skybasic";
      stage = "STARS";
    } else if (path.endsWith("/sky") || path.endsWith("/sunrise_sunset")) {
      name = "gbuffers_skybasic";
      stage = path.endsWith("/sky") ? "SKY" : "SUNSET";
    } else if (path.contains("celestial") || path.contains("end_sky")) {
      name = "gbuffers_skytextured";
      stage = "SUN";
    } else if (path.contains("cloud")) {
      name = "gbuffers_clouds";
      stage = "CLOUDS";
    } else if (path.contains("weather")) {
      name = "gbuffers_weather";
      stage = "RAIN_SNOW";
    } else if (path.contains("particle")) {
      name = "gbuffers_textured_lit";
      stage = "PARTICLES";
    } else if (path.contains("beacon")) {
      name = "gbuffers_beaconbeam";
      stage = "BLOCK_ENTITIES";
    } else if (standaloneGlint) {
      name = "gbuffers_armor_glint";
      stage = "ENTITIES";
    } else if (path.contains("leash") || path.contains("water_mask")) {
      name = "gbuffers_basic";
      stage = "ENTITIES";
    } else if (path.contains("block") && !path.contains("outline")) {
      name = "gbuffers_block";
      stage = "BLOCK_ENTITIES";
    } else if (path.contains("lines")
        || path.contains("outline")
        || path.contains("debug")
        || path.contains("lightning")) {
      name = "gbuffers_basic";
      stage = "OUTLINE";
    } else if (path.contains("crumbling")) {
      name = "gbuffers_damagedblock";
      stage = "DESTROY";
    } else if (path.contains("text") || path.contains("world_border")) {
      name = "gbuffers_textured";
      stage = "WORLD_BORDER";
    } else if (path.contains("end_portal") || path.contains("end_gateway") || blockEntity) {
      name = "gbuffers_block";
      stage = "BLOCK_ENTITIES";
    } else if (path.contains("translucent")
        && !pipeline.getShaderDefines().flags().contains("EMISSIVE")) {
      name = "gbuffers_entities_translucent";
      stage = "ENTITIES";
    } else {
      name = "gbuffers_entities";
      stage = "ENTITIES";
    }
    if (hand) {
      boolean translucent = path.contains("translucent");
      // The standalone enchantment overlay stays a glint pass in first person. Its
      // adapter still applies the hand projection/depth convention. Combined vanilla
      // item/entity materials retain their existing base hand program.

      if (!standaloneGlint) name = translucent ? "gbuffers_hand_water" : "gbuffers_hand";
      stage = translucent ? "HAND_TRANSLUCENT" : "HAND_SOLID";
    }
    return new SceneProgram(shadow ? "shadow" : name, PackEnvironment.stage(stage));
  }
}
