// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;

/** Owns the texture and every view into it; close views before releasing their storage. */
public final class PackTexture implements AutoCloseable {
  public final GpuTexture texture;
  public final GpuTextureView sampled;
  private final List<GpuTextureView> levels = new ArrayList<>();

  public PackTexture(
      GpuDevice device, String name, GpuFormat format, int width, int height, int mips) {
    texture =
        device.createTexture(
            "shader pack " + name,
            GpuTexture.USAGE_COPY_DST
                | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_RENDER_ATTACHMENT,
            format,
            width,
            height,
            1,
            mips);
    try {
      sampled = device.createTextureView(texture);
      try {
        for (int i = 0; i < mips; i++) levels.add(device.createTextureView(texture, i, 1));
      } catch (RuntimeException failure) {
        levels.forEach(GpuTextureView::close);
        sampled.close();
        throw failure;
      }
    } catch (RuntimeException failure) {
      texture.close();
      throw failure;
    }
  }

  public GpuTextureView level(int level) {
    return levels.get(level);
  }

  @Override
  public void close() {
    levels.forEach(GpuTextureView::close);
    sampled.close();
    texture.close();
  }
}
