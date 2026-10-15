// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.geometry;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.lwjgl.system.MemoryUtil;

/** Adds face normals to the transformed quads emitted by the end portal renderer. */
public final class PortalGeometry {
  public static final int STRIDE = 16;
  public static final int NORMAL_OFFSET = 12;
  public static final VertexFormat FORMAT =
      VertexFormat.builder(0)
          .addAttribute("Position", GpuFormat.RGB32_FLOAT)
          .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
          .build();

  private PortalGeometry() {}

  /**
   * Called after the fourth position is written; all four vertices are still in CPU mesh memory.
   */
  public static void finishQuad(long lastVertex) {
    long a = lastVertex - 3L * STRIDE;
    long b = a + STRIDE;
    long c = b + STRIDE;
    float abx = MemoryUtil.memGetFloat(b) - MemoryUtil.memGetFloat(a);
    float aby = MemoryUtil.memGetFloat(b + 4) - MemoryUtil.memGetFloat(a + 4);
    float abz = MemoryUtil.memGetFloat(b + 8) - MemoryUtil.memGetFloat(a + 8);
    float acx = MemoryUtil.memGetFloat(c) - MemoryUtil.memGetFloat(a);
    float acy = MemoryUtil.memGetFloat(c + 4) - MemoryUtil.memGetFloat(a + 4);
    float acz = MemoryUtil.memGetFloat(c + 8) - MemoryUtil.memGetFloat(a + 8);
    float nx = aby * acz - abz * acy;
    float ny = abz * acx - abx * acz;
    float nz = abx * acy - aby * acx;
    float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
    if (!(length > 0) || !Float.isFinite(length))
      throw new IllegalArgumentException("End portal emitted a degenerate quad");
    for (int i = 0; i < 4; i++)
      TerrainVertexWriter.putNormal(
          a + (long) i * STRIDE + NORMAL_OFFSET, nx / length, ny / length, nz / length);
  }
}
