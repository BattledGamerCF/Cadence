package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import dev.cadence.metal.backend.MetalCompute;
import dev.cadence.shaders.compile.ShaderCompatibilityCompiler;
import dev.cadence.shaders.pack.ShaderPackException;
import dev.cadence.shaders.pack.ShaderProperties;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cadence: a pack's Iris storage resources. Custom images (shaders.properties
 * {@code image.<name> = <samplerName> <pixelFormat> <internalFormat> <pixelType> <clear> <relative> <w> <h> [<d>]}: a 2D or
 * 3D texture shaders load and store as {@code name} and sample as {@code samplerName}; relative sizes are fractions of the
 * frame; clear: zeroed every frame) and buffer objects ({@code bufferObject.<index> = <bytes>} or
 * {@code <bytes per pixel> true <scaleX> <scaleY>}). Made on first use, remade when a relative size changes.
 */
public final class PackStorage implements AutoCloseable {
  public record ImageSpec(
      String name, String samplerName, GpuFormat format, String glslFormat, int dims, boolean clear, boolean relative,
      float[] size) {}

  public record BufferSpec(int index, long bytes, boolean relative, float scaleX, float scaleY) {}

  private final Map<String, ImageSpec> images;
  private final Map<String, ImageSpec> bySampler = new HashMap<>();
  private final Map<Integer, BufferSpec> buffers;
  private final Map<String, long[]> imageHandles = new HashMap<>(); // name -> {handle, width, height, depth}
  private final Map<Integer, long[]> bufferHandles = new HashMap<>(); // index -> {handle, size}
  private int width, height;

  public PackStorage(ShaderProperties properties) throws ShaderPackException {
    images = images(properties);
    buffers = buffers(properties);
    for (ImageSpec s : images.values()) bySampler.put(s.samplerName(), s);
  }

  public boolean isEmpty() {
    return images.isEmpty() && buffers.isEmpty();
  }

  /** What the translator needs to declare the pack's images and their samplers. */
  public static ShaderCompatibilityCompiler.StorageContext context(ShaderProperties properties) throws ShaderPackException {
    Map<String, String> formats = new HashMap<>();
    java.util.Set<String> samplers = new java.util.HashSet<>();
    for (ImageSpec s : images(properties).values()) {
      formats.put(s.name(), s.glslFormat());
      samplers.add(s.samplerName());
    }
    return new ShaderCompatibilityCompiler.StorageContext(formats, samplers);
  }

  static Map<String, ImageSpec> images(ShaderProperties properties) throws ShaderPackException {
    Map<String, ImageSpec> result = new LinkedHashMap<>();
    for (var e : new TreeMap<>(properties.values()).entrySet()) {
      if (!e.getKey().startsWith("image.")) continue;
      String name = e.getKey().substring(6);
      String[] f = e.getValue().trim().split("\\s+");
      if (f.length < 8)
        throw new ShaderPackException("Custom image " + name + ": expected <sampler> <format> <internalFormat> <type> <clear> <relative> <size...>");
      boolean relative = Boolean.parseBoolean(f[5]);
      int sizes = f.length - 6;
      float[] size = new float[sizes];
      for (int i = 0; i < sizes; i++) size[i] = Float.parseFloat(f[6 + i]);
      int dims = relative ? 2 : sizes >= 3 ? 3 : 2;
      if (!relative && sizes == 1) throw new ShaderPackException("Custom image " + name + ": 1D images are not supported yet");
      String internal = f[2].toUpperCase(Locale.ROOT);
      result.put(name, new ImageSpec(name, f[0], PackFormats.color(internal), glslFormat(internal), dims, Boolean.parseBoolean(f[4]),
          relative, size));
    }
    return result;
  }

  static Map<Integer, BufferSpec> buffers(ShaderProperties properties) throws ShaderPackException {
    Map<Integer, BufferSpec> result = new TreeMap<>();
    for (var e : properties.values().entrySet()) {
      if (!e.getKey().startsWith("bufferObject.")) continue;
      int index = Integer.parseInt(e.getKey().substring(13).trim());
      String[] f = e.getValue().trim().split("\\s+");
      long bytes = Long.parseLong(f[0]);
      boolean relative = f.length >= 4 && Boolean.parseBoolean(f[1]);
      result.put(index, new BufferSpec(index, bytes, relative, relative ? Float.parseFloat(f[2]) : 0, relative ? Float.parseFloat(f[3]) : 0));
    }
    return result;
  }

  /** OpenGL internal format -> GLSL image format qualifier (RGB ones are stored as RGBA). */
  static String glslFormat(String internal) {
    return switch (internal) {
      case "RGB8" -> "rgba8";
      case "RGB16F" -> "rgba16f";
      case "RGB32F" -> "rgba32f";
      case "RGB16" -> "rgba16";
      case "RGB8_SNORM" -> "rgba8_snorm";
      case "R11F_G11F_B10F", "RG11B10F" -> "r11f_g11f_b10f";
      case "RGB10_A2" -> "rgb10_a2";
      case "RGB10_A2UI" -> "rgb10_a2ui";
      default -> internal.toLowerCase(Locale.ROOT);
    };
  }

  /** Before the frame's passes: sizes follow the frame (relative ones), clears those that ask for it. */
  public void beginFrame(int frameWidth, int frameHeight) {
    if (frameWidth != width || frameHeight != height) {
      width = frameWidth;
      height = frameHeight;
      for (ImageSpec s : images.values())
        if (s.relative()) release(imageHandles.remove(s.name()));
      for (BufferSpec b : buffers.values())
        if (b.relative()) {
          long[] h = bufferHandles.remove(b.index());
          if (h != null) MetalCompute.release(h[0]);
        }
    }
    for (ImageSpec s : images.values()) {
      long[] h = image(s);
      if (s.clear()) MetalCompute.clearTexture(h[0], s.format().blockSize());
    }
  }

  private long[] image(ImageSpec s) {
    long[] h = imageHandles.get(s.name());
    if (h != null) return h;
    int w, hh, d = 1;
    if (s.relative()) {
      w = Math.max(1, Math.round(width * s.size()[0]));
      hh = Math.max(1, Math.round(height * (s.size().length > 1 ? s.size()[1] : s.size()[0])));
    } else {
      w = (int) s.size()[0];
      hh = (int) s.size()[1];
      if (s.dims() == 3) d = (int) s.size()[2];
    }
    // (sizes from the pack: within Metal's limits, as raw textures; an invalid descriptor made Metal abort the whole game)
    int limit = s.dims() == 3 ? 2048 : 16384;
    if (w < 1 || hh < 1 || d < 1 || w > limit || hh > limit || d > limit
        || (long) w * hh * d * s.format().blockSize() > 256L << 20)
      throw new UnsupportedOperationException("Image " + s.name() + " is " + w + "x" + hh + (s.dims() == 3 ? "x" + d : "")
          + ", beyond what Metal can make (1 to " + limit + " per side, 256 MB)");
    long texture = MetalCompute.texture(s.format(), s.dims(), w, hh, d, false);
    h = new long[] {texture, w, hh, d};
    imageHandles.put(s.name(), h);
    MetalCompute.clearTexture(texture, s.format().blockSize()); // defined from the start, clear or not
    return h;
  }

  /** The native texture of image or sampler {@code name}, or 0 when the pack has none of that name. */
  public long imageHandle(String name) {
    ImageSpec s = images.get(name);
    if (s == null) s = bySampler.get(name);
    return s == null ? 0 : image(s)[0];
  }

  public boolean hasImage(String name) {
    return images.containsKey(name) || bySampler.containsKey(name);
  }

  /** The native buffer of buffer object {@code index} (0 if the pack declares none). */
  public long bufferHandle(int index) {
    BufferSpec b = buffers.get(index);
    if (b == null) return 0;
    long[] h = bufferHandles.get(index);
    if (h == null) {
      long size;
      try {
        size = b.relative() ? Math.multiplyExact(Math.multiplyExact(b.bytes(), (long) Math.max(1, Math.round(width * b.scaleX()))),
            (long) Math.max(1, Math.round(height * b.scaleY()))) : b.bytes();
      } catch (ArithmeticException e) {
        size = Long.MAX_VALUE;
      }
      // (a buffer the GPU can't give came back nil and was filled anyway: a native crash; now a refusal with the size)
      long handle = MetalCompute.buffer(Math.max(16, size));
      if (handle == 0)
        throw new UnsupportedOperationException("Buffer object " + index + " asks for " + size + " bytes, more than this Mac's GPU can give");
      h = new long[] {handle, size};
      bufferHandles.put(index, h);
    }
    return h[0];
  }

  private static void release(long[] h) {
    if (h != null) MetalCompute.release(h[0]);
  }

  @Override
  public void close() {
    for (long[] h : imageHandles.values()) release(h);
    for (long[] h : bufferHandles.values()) release(h);
    imageHandles.clear();
    bufferHandles.clear();
  }
}
