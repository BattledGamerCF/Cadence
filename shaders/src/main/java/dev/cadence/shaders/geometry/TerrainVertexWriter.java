// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.geometry;

import org.lwjgl.system.MemoryUtil;

/**
 * Computes the public shader-pack tangent and quad-center attributes from actual emitted geometry.
 */
public final class TerrainVertexWriter {
  private TerrainVertexWriter() {}

  public static void writeMetadata(long pointer, float x, float y, float z) {
    writeMetadata(pointer, x, y, z, TerrainShaderGeometry.currentBlock().layout());
  }

  public static void writeMetadata(
      long pointer, float x, float y, float z, TerrainShaderGeometry.Layout layout) {
    TerrainShaderGeometry.SectionContext context = TerrainShaderGeometry.currentBlock();
    if (context.layout() != layout)
      throw new IllegalStateException("Terrain buffer layout differs from its section snapshot");
    if (layout == TerrainShaderGeometry.Layout.COMPACT) {
      MemoryUtil.memPutInt(pointer + TerrainShaderGeometry.ENTITY_OFFSET, context.packedMetadata());
      return;
    }
    MemoryUtil.memPutFloat(pointer + TerrainShaderGeometry.ENTITY_OFFSET, context.materialId);
    MemoryUtil.memPutFloat(pointer + TerrainShaderGeometry.ENTITY_OFFSET + 4, context.renderType);
    MemoryUtil.memPutFloat(
        pointer + TerrainShaderGeometry.MID_BLOCK_OFFSET, (context.midX - x) * 64);
    MemoryUtil.memPutFloat(
        pointer + TerrainShaderGeometry.MID_BLOCK_OFFSET + 4, (context.midY - y) * 64);
    MemoryUtil.memPutFloat(
        pointer + TerrainShaderGeometry.MID_BLOCK_OFFSET + 8, (context.midZ - z) * 64);
  }

  public static void putNormal(long pointer, float x, float y, float z) {
    MemoryUtil.memPutByte(pointer, snorm(x));
    MemoryUtil.memPutByte(pointer + 1, snorm(y));
    MemoryUtil.memPutByte(pointer + 2, snorm(z));
    MemoryUtil.memPutByte(pointer + 3, (byte) 0);
  }

  /** lastVertex is resolved after buffer growth; never retain pointers across reserve calls. */
  public static void finishQuad(long lastVertex) {
    finishQuad(lastVertex, TerrainShaderGeometry.layout());
  }

  public static void finishQuad(long lastVertex, TerrainShaderGeometry.Layout layout) {
    int stride = layout.stride();
    long a = lastVertex - 3L * stride;
    long b = a + stride;
    long c = b + stride;
    long d = c + stride;
    float e1x = MemoryUtil.memGetFloat(b) - MemoryUtil.memGetFloat(a);
    float e1y = MemoryUtil.memGetFloat(b + 4) - MemoryUtil.memGetFloat(a + 4);
    float e1z = MemoryUtil.memGetFloat(b + 8) - MemoryUtil.memGetFloat(a + 8);
    float e2x = MemoryUtil.memGetFloat(c) - MemoryUtil.memGetFloat(a);
    float e2y = MemoryUtil.memGetFloat(c + 4) - MemoryUtil.memGetFloat(a + 4);
    float e2z = MemoryUtil.memGetFloat(c + 8) - MemoryUtil.memGetFloat(a + 8);
    float nx = e1y * e2z - e1z * e2y;
    float ny = e1z * e2x - e1x * e2z;
    float nz = e1x * e2y - e1y * e2x;
    float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
    if (normalLength > 1.0e-8f) {
      nx /= normalLength;
      ny /= normalLength;
      nz /= normalLength;
    } else {
      // Degenerate faces retain the model's supplied normal, avoiding NaNs in pack lighting.
      nx = MemoryUtil.memGetByte(a + TerrainShaderGeometry.NORMAL_OFFSET) / 127.0f;
      ny = MemoryUtil.memGetByte(a + TerrainShaderGeometry.NORMAL_OFFSET + 1) / 127.0f;
      nz = MemoryUtil.memGetByte(a + TerrainShaderGeometry.NORMAL_OFFSET + 2) / 127.0f;
    }
    float du1 = MemoryUtil.memGetFloat(b + 16) - MemoryUtil.memGetFloat(a + 16);
    float dv1 = MemoryUtil.memGetFloat(b + 20) - MemoryUtil.memGetFloat(a + 20);
    float du2 = MemoryUtil.memGetFloat(c + 16) - MemoryUtil.memGetFloat(a + 16);
    float dv2 = MemoryUtil.memGetFloat(c + 20) - MemoryUtil.memGetFloat(a + 20);
    float determinant = du1 * dv2 - du2 * dv1;
    float tx;
    float ty;
    float tz;
    float bx = 0;
    float by = 0;
    float bz = 0;
    if (Math.abs(determinant) > 1.0e-12f) {
      float reciprocal = 1.0f / determinant;
      tx = (dv2 * e1x - dv1 * e2x) * reciprocal;
      ty = (dv2 * e1y - dv1 * e2y) * reciprocal;
      tz = (dv2 * e1z - dv1 * e2z) * reciprocal;
      bx = (du1 * e2x - du2 * e1x) * reciprocal;
      by = (du1 * e2y - du2 * e1y) * reciprocal;
      bz = (du1 * e2z - du2 * e1z) * reciprocal;
      float normalProjection = tx * nx + ty * ny + tz * nz;
      tx -= normalProjection * nx;
      ty -= normalProjection * ny;
      tz -= normalProjection * nz;
    } else {
      tx = Math.abs(ny) > 0.9f ? 1 : ny;
      ty = Math.abs(ny) > 0.9f ? 0 : -nx;
      tz = 0;
    }
    float tangentLength = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
    if (tangentLength <= 1.0e-8f) {
      tx = 1;
      ty = 0;
      tz = 0;
    } else {
      tx /= tangentLength;
      ty /= tangentLength;
      tz /= tangentLength;
    }
    // The pack contract uses cross(tangent, normal), rather than cross(normal, tangent).
    float handedness =
        (ty * nz - tz * ny) * bx + (tz * nx - tx * nz) * by + (tx * ny - ty * nx) * bz < 0 ? -1 : 1;
    float midU =
        (MemoryUtil.memGetFloat(a + 16)
                + MemoryUtil.memGetFloat(b + 16)
                + MemoryUtil.memGetFloat(c + 16)
                + MemoryUtil.memGetFloat(d + 16))
            * 0.25f;
    float midV =
        (MemoryUtil.memGetFloat(a + 20)
                + MemoryUtil.memGetFloat(b + 20)
                + MemoryUtil.memGetFloat(c + 20)
                + MemoryUtil.memGetFloat(d + 20))
            * 0.25f;
    for (int vertex = 0; vertex < 4; vertex++) {
      long pointer = a + (long) vertex * stride;
      putNormal(pointer + TerrainShaderGeometry.NORMAL_OFFSET, nx, ny, nz);
      if (layout.packedMidUv()) {
        MemoryUtil.memPutShort(pointer + layout.midUvOffset(), packUv(midU));
        MemoryUtil.memPutShort(pointer + layout.midUvOffset() + 2, packUv(midV));
      } else {
        MemoryUtil.memPutFloat(pointer + layout.midUvOffset(), midU);
        MemoryUtil.memPutFloat(pointer + layout.midUvOffset() + 4, midV);
      }
      long tangent = pointer + layout.tangentOffset();
      MemoryUtil.memPutByte(tangent, snorm(tx));
      MemoryUtil.memPutByte(tangent + 1, snorm(ty));
      MemoryUtil.memPutByte(tangent + 2, snorm(tz));
      MemoryUtil.memPutByte(tangent + 3, snorm(handedness));
    }
  }

  private static byte snorm(float value) {
    return (byte) Math.round(Math.clamp(value, -1.0f, 1.0f) * 127.0f);
  }

  /** An atlas coordinate (0..1) as a 16-bit fraction of 65536 (clamped to the representable range). */
  private static short packUv(float uv) {
    int v = Math.round(uv * 65536f);
    return (short) Math.max(0, Math.min(65535, v));
  }
}
