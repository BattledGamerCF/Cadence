// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.compile;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_optimization_level;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_optimization_level_performance;

import com.mojang.renderpearl.api.pipeline.ShaderType;

/** Optional stage-specific SPIR-V optimization before frontend reflection and linkage. */
public final class ShadercFragmentOptimization {
  public static final String PROPERTY = "cadence.shaders.optimizeFragmentSpirv";
  public static final String VERTEX_PROPERTY = "cadence.shaders.optimizeVertexSpirv";
  public static final String PACK_SHADER = "cadence_shaders:pack";
  // Pipeline caches do not include mutable JVM properties. Read once, so a process cannot reuse a
  // cached pipeline under a newly selected mode; changing this experiment requires a restart.
  private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
  private static final boolean VERTEX_ENABLED = Boolean.getBoolean(VERTEX_PROPERTY);

  private ShadercFragmentOptimization() {}

  public static boolean enabled() {
    return ENABLED;
  }

  public static boolean vertexEnabled() {
    return VERTEX_ENABLED;
  }

  /** Retains the frontend's debug information, target, includes, macros, and binding options. */
  public static long configure(long options, String name, ShaderType stage) {
    return configure(options, name, stage, ENABLED, VERTEX_ENABLED);
  }

  static long configure(long options, String name, ShaderType stage, boolean enabled) {
    return configure(options, name, stage, enabled, false);
  }

  static long configure(
      long options, String name, ShaderType stage, boolean fragmentEnabled, boolean vertexEnabled) {
    if (PACK_SHADER.equals(name)
        && ((fragmentEnabled && stage == ShaderType.FRAGMENT)
            || (vertexEnabled && stage == ShaderType.VERTEX)))
      shaderc_compile_options_set_optimization_level(
          options, shaderc_optimization_level_performance);
    return options;
  }
}
