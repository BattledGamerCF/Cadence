package dev.cadence.shaders.runtime;

import static org.lwjgl.util.shaderc.Shaderc.*;

import dev.cadence.metal.backend.MetalCompute;
import dev.cadence.metal.backend.MetalStorage;
import dev.cadence.shaders.compile.TranslatedProgram;
import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * Cadence: an Iris compute program to a Metal kernel: its translated GLSL to SPIR-V (shaderc, Vulkan rules as the game's
 * own shaders), to Metal source (MetalCompute.translateKernel: resource slots, sampler states compiled in), to a
 * pipeline; and how many threadgroups a dispatch of it has.
 */
public final class PackCompute {
  private PackCompute() {
  }

  static MetalCompute.Kernel compile(
      TranslatedProgram program, List<MetalCompute.SamplerState> samplers, List<MetalStorage.Slot> storage, boolean relaxed) {
    ByteBuffer spirv = spirv(program.vertexSource(), program.label());
    try {
      String[] msl = MetalCompute.translateKernel(spirv, samplers, storage);
      return MetalCompute.kernel(msl[0], msl[1], relaxed);
    } catch (RuntimeException e) {
      throw new IllegalStateException("Compute program " + program.label() + ": " + e.getMessage(), e);
    } finally {
      MemoryUtil.memFree(spirv);
    }
  }

  /** GLSL to SPIR-V (a buffer from memAlloc: the caller frees it). */
  public static ByteBuffer spirv(String glsl, String label) {
    long compiler = shaderc_compiler_initialize();
    long options = shaderc_compile_options_initialize();
    ByteBuffer source = MemoryUtil.memUTF8(glsl, false), name = MemoryUtil.memUTF8(label), entry = MemoryUtil.memUTF8("main");
    long result = 0;
    try {
      shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
      shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
      result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, name, entry, options);
      if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
        throw new IllegalStateException("Compute program " + label + ": " + shaderc_result_get_error_message(result));
      ByteBuffer bytes = shaderc_result_get_bytes(result);
      ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
      copy.put(bytes).flip();
      return copy;
    } finally {
      if (result != 0) shaderc_result_release(result);
      shaderc_compile_options_release(options);
      shaderc_compiler_release(compiler);
      MemoryUtil.memFree(source);
      MemoryUtil.memFree(name);
      MemoryUtil.memFree(entry);
    }
  }

  /**
   * Threadgroups of a dispatch, as Iris counts them: const ivec3 workGroups as given; otherwise const vec2
   * workGroupsRender (default 1.0, 1.0) of the frame's size, divided by the local size, rounded up.
   */
  static int[] groups(PackPrograms.Source source, TranslatedProgram program, int frameWidth, int frameHeight) {
    var constants = source.directives().constants();
    String fixed = constants.get("workGroups");
    if (fixed != null) {
      int[] v = numbers(fixed);
      if (v.length == 1) return new int[] {v[0], v[0], v[0]}; // ivec3(n)
      return new int[] {v[0], v.length > 1 ? v[1] : 1, v.length > 2 ? v[2] : 1};
    }
    float sx = 1, sy = 1;
    String render = constants.get("workGroupsRender");
    if (render != null) {
      String[] parts = render.substring(render.indexOf('(') + 1, render.lastIndexOf(')')).split(",");
      sx = Float.parseFloat(parts[0].strip().replaceFirst("[fF]$", ""));
      sy = Float.parseFloat(parts[parts.length > 1 ? 1 : 0].strip().replaceFirst("[fF]$", ""));
    }
    List<Integer> local = program.drawBuffers();
    return new int[] {
      (int) Math.ceil(Math.ceil(frameWidth * sx) / local.get(0)), (int) Math.ceil(Math.ceil(frameHeight * sy) / local.get(1)), 1
    };
  }

  private static int[] numbers(String constructor) {
    String inside = constructor.substring(constructor.indexOf('(') + 1, constructor.lastIndexOf(')'));
    String[] parts = inside.split(",");
    int[] v = new int[parts.length];
    for (int i = 0; i < parts.length; i++) v[i] = Integer.parseInt(parts[i].strip());
    return v;
  }
}
