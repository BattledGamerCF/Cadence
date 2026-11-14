package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
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
      for (var entry : found.entrySet()) {
        Identifier file = entry.getKey();
        String path = file.getPath();
        boolean isNormal = path.endsWith("_n.png");
        Identifier id = file.withPath(path.substring("textures/".length(), path.length() - 6));
        var sprite = atlas.getSprite(id);
        if (!sprite.contents().name().equals(id)) continue;
        int sw = sprite.contents().width(), sh = sprite.contents().height();
        BufferedImage image;
        try (var input = entry.getValue().open()) {
          image = PackTextures.decodeImage(input.readNBytes(32 * 1024 * 1024 + 1), path);
        } catch (IOException bad) {
          continue;
        }
        // (an animated texture's strip: its first frame, as wide as the image)
        int iw = image.getWidth(), ih = Math.min(image.getHeight(), sprite.isAnimated() ? iw * sh / sw : image.getHeight());
        int[] data = new int[sw * sh];
        for (int y = 0; y < sh; y++)
          for (int x = 0; x < sw; x++) {
            int argb = image.getRGB(x * iw / sw, y * ih / sh);
            data[y * sw + x] = argb << 8 | argb >>> 24;
          }
        int x0 = Math.round(sprite.getU0() * width), y0 = Math.round(sprite.getV0() * height);
        int padding = x0 - sprite.getX();
        if (padding != y0 - sprite.getY()) continue;
        try {
          MaterialAtlas.blit(isNormal ? n : s, width, height, x0, y0, sw, sh, padding, new MaterialPalette.Tile(sw, sh, data, ""));
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
