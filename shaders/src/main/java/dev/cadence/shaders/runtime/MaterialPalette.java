// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Author-defined material recipes. Generation happens at atlas loading, never in a draw call. */
public record MaterialPalette(Map<String, Tile> tiles) {
  // RGBA = metalness, emission, roughness, subsurface. Roughness zero means "unannotated".
  public record Tile(int width, int height, int[] rgba, String albedoSha256) {
    public Tile {
      rgba = rgba.clone();
    }

    @Override
    public int[] rgba() {
      return rgba.clone();
    }

    public boolean uniform() {
      return width == 1 && height == 1;
    }

    public boolean matches(byte[] albedo, int spriteWidth, int spriteHeight, boolean animated) {
      return uniform()
          || (!animated
              && width == spriteWidth
              && height == spriteHeight
              && albedoSha256.equals(sha256(albedo)));
    }

    public int pixel(int x, int y) {
      return rgba[uniform() ? 0 : y * width + x];
    }
  }

  public static MaterialPalette parse(String json) throws IOException {
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      if (root.get("version").getAsInt() != 1)
        throw new IllegalArgumentException("version must be 1");
      Map<String, Integer> presets = new LinkedHashMap<>();
      for (var entry : root.getAsJsonObject("presets").entrySet()) {
        JsonObject p = entry.getValue().getAsJsonObject();
        int metal = channel(p, "metalness", 0), emission = channel(p, "emission", 0);
        int rough = channel(p, "roughness", 1), subsurface = channel(p, "subsurface", 0);
        if (rough == 0) throw new IllegalArgumentException("roughness must be at least 1/255");
        presets.put(entry.getKey(), metal << 24 | emission << 16 | rough << 8 | subsurface);
      }
      Map<String, Tile> tiles = new LinkedHashMap<>();
      var sprites = root.getAsJsonObject("sprites");
      if (sprites.size() > 4096) throw new IllegalArgumentException("too many material sprites");
      for (var entry : sprites.entrySet()) {
        if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || entry.getKey().contains(".."))
          throw new IllegalArgumentException("invalid sprite identifier " + entry.getKey());
        JsonObject recipe = entry.getValue().getAsJsonObject();
        Tile tile;
        if (recipe.has("preset")) {
          tile =
              new Tile(1, 1, new int[] {preset(presets, recipe.get("preset").getAsString())}, "");
        } else {
          var rows = recipe.getAsJsonArray("mask");
          int h = rows.size(), w = h == 0 ? 0 : rows.get(0).getAsString().length();
          if (w < 1 || h < 1 || w > 256 || h > 256)
            throw new IllegalArgumentException("invalid mask size");
          String hash = recipe.get("albedoSha256").getAsString();
          if (!hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("mask requires albedo SHA-256");
          var legend = recipe.getAsJsonObject("legend");
          int[] pixels = new int[w * h];
          for (int y = 0; y < h; y++) {
            String row = rows.get(y).getAsString();
            if (row.length() != w) throw new IllegalArgumentException("ragged material mask");
            for (int x = 0; x < w; x++) {
              String symbol = row.substring(x, x + 1);
              if (!legend.has(symbol))
                throw new IllegalArgumentException("unknown mask symbol " + symbol);
              pixels[y * w + x] = preset(presets, legend.get(symbol).getAsString());
            }
          }
          tile = new Tile(w, h, pixels, hash);
        }
        tiles.put(entry.getKey(), tile);
      }
      return new MaterialPalette(Map.copyOf(tiles));
    } catch (RuntimeException failure) {
      throw new IOException("Invalid material palette: " + failure.getMessage(), failure);
    }
  }

  private static int preset(Map<String, Integer> presets, String name) {
    Integer value = presets.get(name);
    if (value == null) throw new IllegalArgumentException("unknown material preset " + name);
    return value;
  }

  private static int channel(JsonObject p, String name, double fallback) {
    double value = p.has(name) ? p.get(name).getAsDouble() : fallback;
    if (!Double.isFinite(value) || value < 0 || value > 1)
      throw new IllegalArgumentException(name + " must be in [0,1]");
    return (int) Math.round(value * 255);
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
