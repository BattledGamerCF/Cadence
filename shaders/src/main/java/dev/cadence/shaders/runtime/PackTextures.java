// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cadence.metal.MetalGpuSampler;
import dev.cadence.shaders.compile.ShaderCompatibilityCompiler.ShadowComparison;
import dev.cadence.shaders.pack.ShaderPack;
import dev.cadence.shaders.pack.ShaderProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Decodes user pack images once; GPU textures and samplers are reused for every frame. */
public final class PackTextures implements AutoCloseable {
  public final GpuSampler linear, nearest, repeat;
  private final GpuSampler comparison;
  public final PackTexture white, black, normal, noise;
  private final Map<String, PackTexture> custom = new HashMap<>();
  private final List<PackTexture> owned = new ArrayList<>();
  private final GpuDevice device;
  private final PackAlbedoSamplers albedoSamplers;
  private Map<String, Decoded> decoded = Map.of();

  /** Cadence: a pack image as RGBA8 pixels, ready to upload. */
  public record Decoded(int width, int height, ByteBuffer pixels) {}

  /**
   * Cadence: every custom texture of these properties decoded to pixels (any thread; PNG decoding was 2 of the 8 stall
   * samples of a pack's first frame). Images that fail are left out: the constructor's own decode reports them.
   */
  public static Map<String, Decoded> decode(ShaderPack pack, ShaderProperties properties) {
    Map<String, Decoded> out = new java.util.concurrent.ConcurrentHashMap<>();
    for (var property : properties.values().entrySet()) {
      if (!property.getKey().startsWith("texture.")) continue;
      String path = property.getValue();
      if (path.contains(" ") || out.containsKey(path)) continue;
      try {
        out.put(path, decodePixels(pack, path));
      } catch (IOException | RuntimeException failure) {
        // reported when the textures are made
      }
    }
    return out;
  }

  private static Decoded decodePixels(ShaderPack pack, String path) throws IOException {
    BufferedImage image = decodeImage(pack.bytes(path), path);
    int width = image.getWidth(), height = image.getHeight();
    int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
    ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
    for (int c : argb) pixels.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >> 24));
    return new Decoded(width, height, pixels.flip());
  }

  public PackTextures(GpuDevice device, ShaderPack pack, ShaderProperties properties)
      throws IOException {
    this(device, pack, properties, ShadowComparison.EMULATED);
  }

  public PackTextures(
      GpuDevice device,
      ShaderPack pack,
      ShaderProperties properties,
      ShadowComparison shadowComparison)
      throws IOException {
    this(device, pack, properties, shadowComparison, Map.of());
  }

  /** Cadence: as above, with images decoded beforehand off the render thread (decode), by pack path. */
  public PackTextures(
      GpuDevice device,
      ShaderPack pack,
      ShaderProperties properties,
      ShadowComparison shadowComparison,
      Map<String, Decoded> decoded)
      throws IOException {
    this.device = device;
    this.decoded = decoded;
    albedoSamplers = new PackAlbedoSamplers(device);
    GpuSampler createdLinear = null,
        createdNearest = null,
        createdRepeat = null,
        createdComparison = null;
    try {
      createdLinear =
          device.createSampler(
              AddressMode.CLAMP_TO_EDGE,
              AddressMode.CLAMP_TO_EDGE,
              FilterMode.LINEAR,
              FilterMode.LINEAR,
              1,
              OptionalDouble.empty());
      createdNearest =
          device.createSampler(
              AddressMode.CLAMP_TO_EDGE,
              AddressMode.CLAMP_TO_EDGE,
              FilterMode.NEAREST,
              FilterMode.NEAREST,
              1,
              OptionalDouble.of(0));
      createdRepeat =
          device.createSampler(
              AddressMode.REPEAT,
              AddressMode.REPEAT,
              FilterMode.LINEAR,
              FilterMode.LINEAR,
              1,
              OptionalDouble.of(0));
      if (shadowComparison == ShadowComparison.HARDWARE)
        createdComparison =
            MetalGpuSampler.comparisonVariant(createdLinear)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Backend advertised depth comparison but could not create its"
                                + " sampler"));
      comparison = createdComparison;
      linear = createdLinear;
      nearest = createdNearest;
      repeat = createdRepeat;
      white = solid("white", 255, 255, 255, 255);
      black = solid("black", 0, 0, 0, 255);
      normal = solid("normal", 128, 128, 255, 255);
      for (var property : properties.values().entrySet()) {
        if (!property.getKey().startsWith("texture.")) continue;
        String path = property.getValue();
        if (path.contains(" "))
          throw new UnsupportedOperationException(
              "Raw custom texture declaration: " + property.getKey());
        PackTexture loaded = image(pack, path);
        custom.put(property.getKey().substring(8), loaded);
        customSamplers.put(loaded, mcmetaSampler(pack, path));
      }
      PackTexture suppliedNoise = custom.get("noise");
      noise = suppliedNoise == null ? generatedNoise() : suppliedNoise;
    } catch (IOException | RuntimeException failure) {
      closeTextures();
      if (createdComparison != null) createdComparison.close();
      if (createdRepeat != null) createdRepeat.close();
      if (createdNearest != null) createdNearest.close();
      if (createdLinear != null) createdLinear.close();
      throw failure;
    }
  }

  /** Compile strategy and binding state must be selected together, once per pack graph. */
  public static ShadowComparison shadowComparison(GpuDevice device) {
    return Boolean.parseBoolean(
                System.getProperty("cadence.shaders.hardwareShadowComparison", "true"))
            && device.getDeviceInfo().underlyingExtensions().contains("depth-comparison-lequal")
        ? ShadowComparison.HARDWARE
        : ShadowComparison.EMULATED;
  }

  public GpuSampler comparisonSampler() {
    return comparison == null ? nearest : comparison;
  }

  public boolean hardwareShadowComparison() {
    return comparison != null;
  }

  private PackTexture solid(String name, int r, int g, int b, int a) {
    ByteBuffer rgba =
        ByteBuffer.allocateDirect(4).put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
    return upload(name, 1, 1, rgba);
  }

  private PackTexture generatedNoise() {
    int size = 256;
    java.util.Random random = new java.util.Random(0x5EED);
    ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
    while (pixels.hasRemaining())
      pixels
          .put((byte) random.nextInt(256))
          .put((byte) random.nextInt(256))
          .put((byte) random.nextInt(256))
          .put((byte) 255);
    return upload("noise", size, size, pixels.flip());
  }

  private PackTexture image(ShaderPack pack, String path) throws IOException {
    Decoded d = decoded.get(path);
    if (d == null) d = decodePixels(pack, path);
    return upload(path, d.width(), d.height(), d.pixels().duplicate());
  }

  /** Inspect the encoded dimensions before an image reader can allocate decoded pixel storage. */
  static BufferedImage decodeImage(byte[] bytes, String path) throws IOException {
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IOException("Unsupported pack texture " + path);
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1 || height < 1 || (long) width * height > 16_777_216L)
          throw new IOException("Pack image exceeds texture size limit: " + path);
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }

  private PackTexture upload(String name, int width, int height, ByteBuffer pixels) {
    PackTexture result = new PackTexture(device, name, GpuFormat.RGBA8_UNORM, width, height, 1);
    owned.add(result);
    device.createCommandEncoder().writeToTexture(result.texture, pixels, 0, 0, 0, 0, width, height);
    return result;
  }

  // Cadence: lookups by program and sampler, cached (asked for every sampler at every bind; the key was a new string
  // each time). Cleared with the textures. Render thread, and the pipeline compile threads (ShaderRuntime.prewarmPipelines).
  private final Map<String, Map<String, java.util.Optional<PackTexture>>> customLookups =
      new java.util.concurrent.ConcurrentHashMap<>();

  public PackTexture custom(String program, String sampler) {
    if (custom.isEmpty()) return null;
    var bySampler = customLookups.computeIfAbsent(program, ignored -> new java.util.concurrent.ConcurrentHashMap<>());
    var known = bySampler.get(sampler);
    if (known == null) {
      known = java.util.Optional.ofNullable(findCustom(program, sampler));
      bySampler.put(sampler, known);
    }
    return known.orElse(null);
  }

  /**
   * Cadence: per screen program, the colour buffers earlier passes of the frame (prepare, deferred, composite, in order)
   * have drawn into. As Iris, a custom texture standing in for colortexN (texture.composite.colortex6 = blueNoise.png)
   * is read only until a pass has written colortexN; after that the program reads the buffer. Bliss reads its blue noise
   * from colortex6 early in the composite chain, then draws its bloom into colortex6 and reads that back: the bloom was
   * the noise (a milky haze by day, an olive grain at night and underwater).
   */
  private Map<String, java.util.Set<Integer>> writtenBefore = Map.of();

  public void setWrittenBefore(Map<String, java.util.Set<Integer>> written) {
    writtenBefore = Map.copyOf(written);
    customLookups.clear();
  }

  private PackTexture findCustom(String program, String sampler) {
    if (sampler.matches("colortex[0-9]+|gcolor|gdepth|gnormal|composite|gaux[1-4]")) {
      var written = writtenBefore.get(program);
      try {
        if (written != null && written.contains(dev.cadence.shaders.pack.ShaderDirectives.colorBufferIndex(sampler))) return null;
      } catch (dev.cadence.shaders.pack.ShaderPackException e) {
        // not a colour buffer name after all: the override applies
      }
    }
    String group =
        program.startsWith("gbuffers_")
            ? "gbuffers"
            : program.startsWith("deferred")
                ? "deferred"
                : program.startsWith("composite") || program.equals("final")
                    ? "composite"
                    : program.startsWith("shadow") ? "shadow" : program.replaceAll("[0-9]+$", "");
    return custom.get(group + "." + sampler);
  }

  // Cadence: each custom texture's sampler, from its .mcmeta ("blur": linear, "clamp": clamped), as vanilla resource
  // packs, OptiFine and Iris read it; without one, nearest and repeating (vanilla's defaults). Complementary's water and
  // cloud texture asks for blur and repeat: clamped, its world-space lookups smeared the edge into streaks.
  private final Map<PackTexture, GpuSampler> customSamplers = new java.util.IdentityHashMap<>();
  private final Map<Integer, GpuSampler> mcmetaSamplers = new HashMap<>();

  private GpuSampler mcmetaSampler(ShaderPack pack, String path) {
    boolean blur = false, clamp = false;
    String meta = (path.startsWith("/") ? path.substring(1) : path) + ".mcmeta";
    try {
      if (pack.contains(meta)) {
        String text = pack.text(meta);
        java.util.regex.Matcher b = java.util.regex.Pattern.compile("\"blur\"\\s*:\\s*(true|false)").matcher(text);
        java.util.regex.Matcher c = java.util.regex.Pattern.compile("\"clamp\"\\s*:\\s*(true|false)").matcher(text);
        if (b.find()) blur = b.group(1).equals("true");
        if (c.find()) clamp = c.group(1).equals("true");
      }
    } catch (Exception e) {
      // unreadable metadata: the defaults
    }
    int key = (blur ? 1 : 0) | (clamp ? 2 : 0);
    boolean linearFilter = blur, clampEdges = clamp;
    return mcmetaSamplers.computeIfAbsent(
        key,
        k ->
            device.createSampler(
                clampEdges ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT,
                clampEdges ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT,
                linearFilter ? FilterMode.LINEAR : FilterMode.NEAREST,
                linearFilter ? FilterMode.LINEAR : FilterMode.NEAREST,
                1,
                OptionalDouble.of(0)));
  }

  /** noisetex's sampler: the pack's noise texture's own (.mcmeta), else linear and repeating. */
  // Cadence: linear, clamped, base level only: a colour buffer with mipmaps in a pass that didn't ask for them
  // (colortexNMipmapEnabled), as Iris samples it; Metal's automatic mip choice inside a branch that differs per pixel
  // otherwise picks blurry levels (Complementary's FXAA turned every edge into dashes)
  private GpuSampler baseLevel;

  public synchronized GpuSampler linearBaseLevel() {
    if (baseLevel == null)
      baseLevel =
          device.createSampler(
              AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1,
              OptionalDouble.of(0));
    return baseLevel;
  }

  public GpuSampler noiseSampler() {
    GpuSampler sampler = customSamplers.get(noise);
    return sampler == null ? repeat : sampler;
  }

  /** The sampler a custom texture is read with (its .mcmeta); linear and clamped for any other texture. */
  public GpuSampler samplerFor(PackTexture texture) {
    GpuSampler sampler = customSamplers.get(texture);
    return sampler == null ? linear : sampler;
  }

  public GpuSampler albedoSampler(
      GpuTextureView texture, GpuTexture blockAtlas, GpuSampler supplied) {
    return albedoSamplers.forTexture(texture, blockAtlas, supplied);
  }

  @Override
  public void close() {
    closeTextures();
    albedoSamplers.close();
    if (comparison != null) comparison.close();
    if (baseLevel != null) baseLevel.close();
    repeat.close();
    nearest.close();
    linear.close();
  }

  private void closeTextures() {
    for (int i = owned.size() - 1; i >= 0; i--) owned.get(i).close();
    owned.clear();
    custom.clear();
    customLookups.clear();
    customSamplers.clear();
    for (GpuSampler sampler : mcmetaSamplers.values()) sampler.close();
    mcmetaSamplers.clear();
  }
}
