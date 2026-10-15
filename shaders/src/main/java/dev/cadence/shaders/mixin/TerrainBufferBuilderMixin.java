// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import dev.cadence.shaders.geometry.TerrainVertexWriter;
import java.nio.ByteOrder;
import net.minecraft.util.ARGB;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Writes the extended layout in one operation, preserving vanilla's packed-vertex fast path. */
@Mixin(BufferBuilder.class)
public abstract class TerrainBufferBuilderMixin {
  @Shadow @Final private VertexFormat format;
  @Shadow private int vertices;

  @Unique
  private static final boolean shaders$littleEndian =
      ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

  @Shadow
  private long beginVertex() {
    throw new AssertionError();
  }

  @Inject(method = "addVertex(FFFIFFIIFFF)V", at = @At("HEAD"), cancellable = true)
  private void shaders$writeExtendedTerrain(
      float x,
      float y,
      float z,
      int color,
      float u,
      float v,
      int overlayCoords,
      int lightCoords,
      float nx,
      float ny,
      float nz,
      CallbackInfo callback) {
    TerrainShaderGeometry.Layout layout = TerrainShaderGeometry.layoutFor(format);
    if (layout == null) return;
    long pointer = beginVertex();
    MemoryUtil.memPutFloat(pointer, x);
    MemoryUtil.memPutFloat(pointer + 4, y);
    MemoryUtil.memPutFloat(pointer + 8, z);
    int abgr = ARGB.toABGR(color);
    MemoryUtil.memPutInt(pointer + 12, shaders$littleEndian ? abgr : Integer.reverseBytes(abgr));
    MemoryUtil.memPutFloat(pointer + 16, u);
    MemoryUtil.memPutFloat(pointer + 20, v);
    MemoryUtil.memPutShort(pointer + 24, (short) (lightCoords & 0xffff));
    MemoryUtil.memPutShort(pointer + 26, (short) (lightCoords >>> 16));
    TerrainVertexWriter.putNormal(pointer + TerrainShaderGeometry.NORMAL_OFFSET, nx, ny, nz);
    TerrainVertexWriter.writeMetadata(pointer, x, y, z, layout);
    if ((vertices & 3) == 0) TerrainVertexWriter.finishQuad(pointer, layout);
    callback.cancel();
  }
}
