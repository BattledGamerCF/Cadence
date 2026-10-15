// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/** Restores pixel-art atlas magnification for packs which do not run vanilla's sampling helper. */
public final class PackAlbedoSamplers implements AutoCloseable {
  private final GpuDevice device;
  private final Map<Settings, GpuSampler> variants = new HashMap<>();
  private boolean closed;

  public PackAlbedoSamplers(GpuDevice device) {
    this.device = device;
  }

  public GpuSampler forTexture(GpuTextureView texture, GpuTexture blockAtlas, GpuSampler supplied) {
    if (closed) throw new IllegalStateException("Pack atlas samplers are closed");
    if (texture.texture() != blockAtlas || supplied.getMagFilter() == FilterMode.NEAREST)
      return supplied;
    Settings settings =
        new Settings(
            supplied.getAddressModeU(),
            supplied.getAddressModeV(),
            supplied.getMinFilter(),
            supplied.getMaxAnisotropy(),
            supplied.getMaxLod());
    return variants.computeIfAbsent(
        settings,
        key ->
            device.createSampler(
                key.u(), key.v(), key.min(), FilterMode.NEAREST, key.anisotropy(), key.maxLod()));
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    variants.values().forEach(GpuSampler::close);
    variants.clear();
  }

  private record Settings(
      AddressMode u, AddressMode v, FilterMode min, int anisotropy, OptionalDouble maxLod) {}
}
