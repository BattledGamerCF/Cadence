// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Advertised capabilities and render stages are matched to the loader's actual implementation. */
public final class PackEnvironment {
  public static final List<String> STAGES =
      List.of(
          "NONE",
          "SKY",
          "SUNSET",
          "CUSTOM_SKY",
          "SUN",
          "MOON",
          "STARS",
          "VOID",
          "TERRAIN_SOLID",
          "TERRAIN_CUTOUT_MIPPED",
          "TERRAIN_CUTOUT",
          "ENTITIES",
          "BLOCK_ENTITIES",
          "DESTROY",
          "OUTLINE",
          "DEBUG",
          "HAND_SOLID",
          "TERRAIN_TRANSLUCENT",
          "TRIPWIRE",
          "PARTICLES",
          "CLOUDS",
          "RAIN_SNOW",
          "WORLD_BORDER",
          "HAND_TRANSLUCENT");

  public static Map<String, String> definitions() {
    Map<String, String> result = new LinkedHashMap<>();
    result.put("MC_VERSION", "260300");
    result.put("MC_GL_VERSION", "330");
    result.put("MC_GLSL_VERSION", "330");
    result.put("MC_OS_MAC", "");
    result.put("MC_HAND_DEPTH", "0.125");
    // Cadence: what OptiFine and Iris define with their default settings (Normal Map and Specular Map on, full render
    // and shadow quality). Cadence supplies a flat normal map and an empty specular map when no resource pack has them.
    result.put("MC_NORMAL_MAP", "");
    result.put("MC_SPECULAR_MAP", "");
    result.put("MC_RENDER_QUALITY", "1.0");
    result.put("MC_SHADOW_QUALITY", "1.0");
    for (int i = 0; i < STAGES.size(); i++)
      result.put("MC_RENDER_STAGE_" + STAGES.get(i), Integer.toString(i));
    // Cadence: the loader implements Iris's pack format (compute, images, buffer objects, the *_translucent programs, eight
    // shadowcolor buffers), so packs see Iris: IS_IRIS and IRIS_VERSION (many packs only take their modern path, or only
    // run at all, under Iris) and the IRIS_FEATURE_ flag of each feature it has. Features it doesn't have stay undefined
    // (block emission in at_midBlock.w, separate hardware shadow samplers, reversed shadow culling, the chunk fade
    // variable) and packs fall back as on an Iris without them. -Dcadence.shaders.irisMacros=false: OptiFine's macros only.
    if (!"false".equals(System.getProperty("cadence.shaders.irisMacros"))) {
      result.put("IS_IRIS", "");
      result.put("IRIS_VERSION", "10800");
      for (String feature : java.util.List.of("CUSTOM_IMAGES", "COMPUTE_SHADERS", "SSBO", "ENTITY_TRANSLUCENT", "HIGHER_SHADOWCOLOR"))
        result.put("IRIS_FEATURE_" + feature, "");
    }
    // Iris's constants as GLSL macros too, not only in custom uniforms (Sildur's Enhanced Default compares biome_category
    // with CAT_THE_END in code): biome categories, precipitation kinds, and BIOME_<NAME> numbered as the biome uniform
    // (FrameUniforms.biomeId: the order net.minecraft.world.level.biome.Biomes declares them; names read without
    // initialising the class).
    for (int i = 0; i < FrameUniforms.CATEGORIES.length; i++) result.put("CAT_" + FrameUniforms.CATEGORIES[i], Integer.toString(i));
    result.put("PPT_NONE", "0");
    result.put("PPT_RAIN", "1");
    result.put("PPT_SNOW", "2");
    int biome = 0;
    for (var field : net.minecraft.world.level.biome.Biomes.class.getDeclaredFields()) {
      if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType() != net.minecraft.resources.ResourceKey.class)
        continue;
      String name = field.getName();
      try { // the biome's own name where the key can be read (a remapped field name would be wrong)
        name = ((net.minecraft.resources.ResourceKey<?>) field.get(null)).identifier().getPath().toUpperCase(java.util.Locale.ROOT);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
        // the field's name
      }
      result.put("BIOME_" + name, Integer.toString(biome++));
    }
    return Map.copyOf(result);
  }

  public static int stage(String name) {
    int stage = STAGES.indexOf(name);
    if (stage < 0) throw new IllegalArgumentException("Unknown render stage " + name);
    return stage;
  }
}
