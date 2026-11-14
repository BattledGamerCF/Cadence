package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

/**
 * Cadence: PBR resource packs (labPBR, OldPBR). A pack's {@code <texture>_n.png} (normals) and {@code <texture>_s.png}
 * (specular) are placed where {@code <texture>} sits in the block atlas, so a shader pack's {@code normals} and
 * {@code specular} samplers read them with the block's own coordinates. Textures without them get a flat normal and no
 * specular, as before. Animated textures use their first frame. Built when the resources or the atlas change.
 */
public final class PbrAtlas implements AutoCloseable {
  /** Flat normal in labPBR's encoding: normal (0.5, 0.5, 1), full AO, no height offset. */
  private static final int FLAT = 0x8080FFFF, NONE = 0;

  private final GpuDevice device;
  private PackTexture normal, specular;
  private GpuTexture source;
  private ResourceManager resources;

  public PbrAtlas(GpuDevice device) {
    this.device = device;
  }

  /** The normal map matching this albedo texture, or null (the caller's flat default). */
  public PackTexture normalFor(GpuTexture albedo) {
    return albedo == source ? normal : null;
  }

  public PackTexture specularFor(GpuTexture albedo) {
    return albedo == source ? specular : null;
  }

  public void refresh(TextureAtlas atlas, ResourceManager manager) throws IOException {
    GpuTexture next = atlas.getTextureView().texture();
    if (next == source && manager == resources) return;
    closeTextures();
    source = next;
    resources = manager;
    int width = next.getWidth(0), height = next.getHeight(0), mips = next.getMipLevels();
    if ((long) width * height > 16_777_216L) return;
    var found = manager.listResources("textures", id -> id.getPath().endsWith("_n.png") || id.getPath().endsWith("_s.png"));
    if (found.isEmpty()) return;
    ByteBuffer n = MemoryUtil.memAlloc(width * height * 4), s = MemoryUtil.memCalloc(width * height * 4);
    int count = 0;
    try {
      for (int i = 0; i < width * height; i++) n.putInt(i * 4, Integer.reverseBytes(FLAT));
      // (decoded on all cores, each map on its own: one a decoder can't read is left flat instead of stopping the pack; then
      // placed in order)
      record Job(boolean normal, int x0, int y0, int padding, int w, int h, Resource file, String path, boolean animated) {
      }
      java.util.List<Job> jobs = new java.util.ArrayList<>();
      for (var entry : found.entrySet()) {
        Identifier file = entry.getKey();
        String path = file.getPath();
        Identifier id = file.withPath(path.substring("textures/".length(), path.length() - 6));
        var sprite = atlas.getSprite(id);
        if (!sprite.contents().name().equals(id)) continue;
        int x0 = Math.round(sprite.getU0() * width), y0 = Math.round(sprite.getV0() * height);
        int padding = x0 - sprite.getX();
        if (padding != y0 - sprite.getY()) continue;
        jobs.add(new Job(path.endsWith("_n.png"), x0, y0, padding, sprite.contents().width(), sprite.contents().height(), entry.getValue(), path,
            sprite.isAnimated()));
      }
      MaterialPalette.Tile[] tiles = jobs.parallelStream().map(job -> {
        try (var input = job.file().open()) {
          BufferedImage image = PackTextures.decodeImage(input.readNBytes(32 * 1024 * 1024 + 1), job.path());
          int sw = job.w(), sh = job.h();
          // (an animated texture's strip: its first frame, as wide as the image)
          int iw = image.getWidth(), ih = Math.min(image.getHeight(), job.animated() ? iw * sh / sw : image.getHeight());
          int[] data = new int[sw * sh];
          for (int y = 0; y < sh; y++)
            for (int x = 0; x < sw; x++) {
              int argb = image.getRGB(x * iw / sw, y * ih / sh);
              data[y * sw + x] = argb << 8 | argb >>> 24;
            }
          return new MaterialPalette.Tile(sw, sh, data, "");
        } catch (IOException | RuntimeException bad) {
          return null;
        }
      }).toArray(MaterialPalette.Tile[]::new);
      for (int i = 0; i < tiles.length; i++) {
        Job job = jobs.get(i);
        if (tiles[i] == null) continue;
        try {
          MaterialAtlas.blit(job.normal() ? n : s, width, height, job.x0(), job.y0(), job.w(), job.h(), job.padding(), tiles[i]);
          count++;
        } catch (IOException outside) {
          // (a sprite at the atlas edge without room for its padding: left flat)
        }
      }
      if (count == 0) return;
      normal = new PackTexture(device, "pbr normals", GpuFormat.RGBA8_UNORM, width, height, mips);
      specular = new PackTexture(device, "pbr specular", GpuFormat.RGBA8_UNORM, width, height, mips);
      MaterialAtlas.uploadMips(device, normal, n, width, height);
      MaterialAtlas.uploadMips(device, specular, s, width, height);
      LoggerFactory.getLogger("cadence_shaders").info("PBR atlas {}x{}: {} normal and specular textures", width, height, count);
    } finally {
      MemoryUtil.memFree(n);
      MemoryUtil.memFree(s);
    }
  }

  private void closeTextures() {
    if (normal != null) normal.close();
    if (specular != null) specular.close();
    normal = specular = null;
  }

  @Override
  public void close() {
    closeTextures();
  }
}
