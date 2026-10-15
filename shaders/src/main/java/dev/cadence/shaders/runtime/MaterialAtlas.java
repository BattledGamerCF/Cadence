// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

/**
 * One optional companion for the block atlas. Source identity includes the reload resource manager.
 */
public final class MaterialAtlas implements AutoCloseable {
  private final GpuDevice device;
  private final MaterialPalette palette;
  public final GpuSampler sampler;
  public final PackTexture fallback;
  private PackTexture atlas;
  private GpuTexture source;
  private ResourceManager resources;
  private int generations, mapped, skipped, overrides;

  public MaterialAtlas(GpuDevice device, MaterialPalette palette) {
    this.device = device;
    this.palette = palette;
    sampler =
        device.createSampler(
            AddressMode.CLAMP_TO_EDGE,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.NEAREST,
            FilterMode.NEAREST,
            1,
            OptionalDouble.empty());
    PackTexture created = null;
    try {
      fallback =
          created = new PackTexture(device, "unannotated material", GpuFormat.RGBA8_UNORM, 1, 1, 1);
      device.createCommandEncoder().clearColorTexture(fallback.texture, new org.joml.Vector4f());
    } catch (RuntimeException failure) {
      if (created != null) created.close();
      sampler.close();
      throw failure;
    }
  }

  public record Stats(int generations, int mapped, int skipped, int overrides, long bytes) {}

  public Stats stats() {
    long bytes = 0;
    if (atlas != null)
      for (int i = 0; i < atlas.texture.getMipLevels(); i++)
        bytes += (long) atlas.texture.getWidth(i) * atlas.texture.getHeight(i) * 4;
    return new Stats(generations, mapped, skipped, overrides, bytes);
  }

  public PackTexture forAlbedo(GpuTexture albedo) {
    return albedo == source && atlas != null ? atlas : fallback;
  }

  public void refresh(TextureAtlas original, ResourceManager manager) throws IOException {
    GpuTexture nextSource = original.getTextureView().texture();
    if (source == nextSource && resources == manager) return;
    int width = nextSource.getWidth(0), height = nextSource.getHeight(0);
    if ((long) width * height > 16_777_216L)
      throw new IOException("Material atlas exceeds 16 million texels");
    var supplied = manager.listResources("shader_materials", id -> id.getPath().endsWith(".png"));
    var ids = new HashSet<>(palette.tiles().keySet());
    for (var id : supplied.keySet())
      ids.add(
          id.getNamespace()
              + ":"
              + id.getPath().substring("shader_materials/".length(), id.getPath().length() - 4));
    if (ids.size() > 4096) throw new IOException("Too many material sprite overrides");
    ByteBuffer pixels = MemoryUtil.memCalloc(width * height * 4);
    PackTexture replacement = null;
    int count = 0, rejected = 0, overrideCount = 0;
    try {
      for (String name : ids) {
        Identifier id = Identifier.parse(name);
        var sprite = original.getSprite(id);
        if (!sprite.contents().name().equals(id)) continue;
        int sw = sprite.contents().width(), sh = sprite.contents().height();
        MaterialPalette.Tile tile = palette.tiles().get(name);
        var override = supplied.get(id.withPath("shader_materials/" + id.getPath() + ".png"));
        if (override != null) {
          try (var input = override.open()) {
            var image = PackTextures.decodeImage(input.readNBytes(32 * 1024 * 1024 + 1), name);
            if (image.getWidth() != sw || image.getHeight() != sh)
              throw new IOException("Material override dimensions differ from sprite: " + name);
            int[] data = new int[sw * sh];
            boolean uniform = true;
            for (int y = 0; y < sh; y++)
              for (int x = 0; x < sw; x++) {
                int argb = image.getRGB(x, y);
                int rgba = argb << 8 | argb >>> 24;
                if ((rgba & 0xff00) == 0)
                  throw new IOException("Override roughness must be nonzero: " + name);
                data[y * sw + x] = rgba;
                uniform &= rgba == data[0];
              }
            // A constant material remains aligned through every animation frame. Varying animated
            // masks need a frame-aware contract; never silently freeze one frame's mask onto
            // another.
            if (sprite.isAnimated() && !uniform) {
              rejected++;
              continue;
            }
            tile =
                uniform
                    ? new MaterialPalette.Tile(1, 1, new int[] {data[0]}, "")
                    : new MaterialPalette.Tile(sw, sh, data, "");
            overrideCount++;
          }
        } else if (tile != null && !tile.uniform()) {
          var albedo = manager.getResource(id.withPath("textures/" + id.getPath() + ".png"));
          boolean matches = false;
          if (albedo.isPresent())
            try (var input = albedo.get().open()) {
              matches =
                  tile.matches(input.readNBytes(32 * 1024 * 1024 + 1), sw, sh, sprite.isAnimated());
            }
          if (!matches) {
            rejected++;
            continue;
          }
        }
        if (tile == null) continue;
        int x = Math.round(sprite.getU0() * width), y = Math.round(sprite.getV0() * height);
        int padding = x - sprite.getX();
        if (padding != y - sprite.getY())
          throw new IOException("Asymmetric sprite padding: " + name);
        blit(pixels, width, height, x, y, sw, sh, padding, tile);
        count++;
      }
      replacement =
          new PackTexture(
              device,
              "material atlas",
              GpuFormat.RGBA8_UNORM,
              width,
              height,
              nextSource.getMipLevels());
      uploadMips(replacement, pixels, width, height);
      PackTexture previous = atlas;
      atlas = replacement;
      replacement = null;
      source = nextSource;
      resources = manager;
      generations++;
      mapped = count;
      skipped = rejected;
      overrides = overrideCount;
      if (previous != null) previous.close();
      LoggerFactory.getLogger("cadence_shaders")
          .info(
              "Material atlas {}x{}: {} mapped sprites, {} incompatible masks skipped, {} resource"
                  + " overrides, {} bytes",
              width,
              height,
              mapped,
              skipped,
              overrides,
              stats().bytes());
    } finally {
      if (replacement != null) replacement.close();
      MemoryUtil.memFree(pixels);
    }
  }

  static void blit(
      ByteBuffer pixels,
      int width,
      int height,
      int x,
      int y,
      int sw,
      int sh,
      int padding,
      MaterialPalette.Tile tile)
      throws IOException {
    if (x - padding < 0 || y - padding < 0 || x + sw + padding > width || y + sh + padding > height)
      throw new IOException("Material sprite outside atlas");
    for (int dy = -padding; dy < sh + padding; dy++)
      for (int dx = -padding; dx < sw + padding; dx++) {
        int color = tile.pixel(Math.clamp(dx, 0, sw - 1), Math.clamp(dy, 0, sh - 1));
        int offset = ((y + dy) * width + x + dx) * 4;
        for (int c = 0; c < 4; c++) pixels.put(offset + c, (byte) (color >>> (24 - c * 8)));
      }
  }

  static void downsample(ByteBuffer input, int width, int height, ByteBuffer output) {
    int w = Math.max(1, width / 2), h = Math.max(1, height / 2);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++)
        for (int c = 0; c < 4; c++) {
          int sum = 0;
          for (int dy = 0; dy < 2; dy++)
            for (int dx = 0; dx < 2; dx++)
              sum +=
                  Byte.toUnsignedInt(
                      input.get(
                          (Math.min(height - 1, y * 2 + dy) * width
                                      + Math.min(width - 1, x * 2 + dx))
                                  * 4
                              + c));
          output.put((y * w + x) * 4 + c, (byte) ((sum + 2) / 4));
        }
  }

  private void uploadMips(PackTexture target, ByteBuffer base, int width, int height) {
    ByteBuffer current = base;
    try {
      for (int level = 0; level < target.texture.getMipLevels(); level++) {
        device
            .createCommandEncoder()
            .writeToTexture(target.texture, current, level, 0, 0, 0, width, height);
        if (level + 1 < target.texture.getMipLevels()) {
          ByteBuffer next =
              MemoryUtil.memAlloc(Math.max(1, width / 2) * Math.max(1, height / 2) * 4);
          downsample(current, width, height, next);
          if (current != base) MemoryUtil.memFree(current);
          current = next;
          width = Math.max(1, width / 2);
          height = Math.max(1, height / 2);
        }
      }
    } finally {
      if (current != base) MemoryUtil.memFree(current);
    }
  }

  @Override
  public void close() {
    if (atlas != null) atlas.close();
    fallback.close();
    sampler.close();
  }
}
