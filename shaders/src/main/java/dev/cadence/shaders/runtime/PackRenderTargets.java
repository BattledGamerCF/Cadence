// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/** Color pairs survive across frames. Geometry writes current; screen passes write alternate. */
public final class PackRenderTargets implements AutoCloseable {
  /**
   * size: Cadence: OptiFine's size.buffer.colortexN, or null for the screen's size: {w, h, 1} relative to the screen
   * ("0.5 0.5") or {w, h, 0} in pixels ("480 270").
   */
  public record BufferSpec(
      GpuFormat format, boolean clear, Vector4fc clearColor, boolean mipmaps, float[] size) {
    public BufferSpec {
      clearColor = clearColor == null ? null : new Vector4f(clearColor);
    }

    public BufferSpec(GpuFormat format, boolean clear, Vector4fc clearColor, boolean mipmaps) {
      this(format, clear, clearColor, mipmaps, null);
    }

    int width(int screen) {
      return size == null ? screen : Math.max(1, Math.round(size[2] != 0 ? screen * size[0] : size[0]));
    }

    int height(int screen) {
      return size == null ? screen : Math.max(1, Math.round(size[2] != 0 ? screen * size[1] : size[1]));
    }
  }

  /** Cadence: a shadow color buffer's settings (see PackPrograms.shadowSpecifications). */
  public record ShadowSpec(
      GpuFormat format, boolean clear, Vector4fc clearColor, boolean mipmaps, boolean nearest) {
    public ShadowSpec {
      clearColor = new Vector4f(clearColor);
    }
  }

  private static final ShadowSpec SHADOW_DEFAULT =
      new ShadowSpec(GpuFormat.RGBA8_UNORM, true, new Vector4f(1), false, false);
  private Map<Integer, ShadowSpec> shadowSpecifications = Map.of();

  private Set<Integer> storageColors = Set.of(), storageShadowColors = Set.of();

  /** Cadence: the colour and shadow colour targets the pack's programs store to (colorimgN, shadowcolorimgN); before any is made. */
  public void setStorageImages(Set<Integer> colors, Set<Integer> shadowColors) {
    storageColors = Set.copyOf(colors);
    storageShadowColors = Set.copyOf(shadowColors);
  }

  /** Cadence: before any shadow color buffer is made. */
  public void setShadowSpecifications(Map<Integer, ShadowSpec> specifications) {
    shadowSpecifications = Map.copyOf(specifications);
  }

  public ShadowSpec shadowSpecification(int index) {
    return shadowSpecifications.getOrDefault(index, SHADOW_DEFAULT);
  }

  private static final BufferSpec DEFAULT =
      new BufferSpec(GpuFormat.RGBA8_UNORM, true, null, false);
  private final GpuDevice device;
  private final boolean lazyClears;
  private final Map<Integer, BufferSpec> specifications;
  private final Map<PackTexture, Vector4fc> pendingColors = new HashMap<>();
  private final Set<PackTexture> pendingDepths = new HashSet<>();
  private final Map<Integer, Pair> colors = new HashMap<>();
  /**
   * Cadence: mipmapped textures whose mip levels still match their base level: nothing drew into, cleared or stored to them
   * since their chain was made. Pegasus's seven composite passes each asked for colortex0's 9-level chain (81 mip passes a
   * frame, most of them remaking identical levels).
   */
  private final Set<PackTexture> mipsCurrent = new HashSet<>();

  public boolean mipsCurrent(PackTexture texture) {
    return mipsCurrent.contains(texture);
  }

  public void markMipsCurrent(PackTexture texture) {
    mipsCurrent.add(texture);
  }

  /** Something other than a render pass of descriptor() wrote texture (a compute image store). */
  public void wrote(PackTexture texture) {
    mipsCurrent.remove(texture);
  }
  private final List<PackTexture> owned = new ArrayList<>();
  /**
   * depthtex0-2, then (Cadence's far terrain through a pack's dh_* programs, Iris's names) dhDepthTex0 at 3 and dhDepthTex1 at 4:
   * those two are made on first use (DH_DEPTH), so a pack or world without far terrain carries neither.
   */
  private final PackTexture[] depths = new PackTexture[5];
  public static final int DH_DEPTH = 3;
  private PackTexture mergedDepth;
  private final PackTexture[] shadowDepths = new PackTexture[2];
  private final Map<Integer, PackTexture> shadowColors = new HashMap<>();
  public final int width, height, shadowSize;
  private Vector4fc fogColor = new Vector4f();

  public PackRenderTargets(
      GpuDevice device,
      Map<Integer, BufferSpec> specifications,
      int width,
      int height,
      int shadowSize) {
    this(
        device,
        specifications,
        width,
        height,
        shadowSize,
        Boolean.getBoolean("cadence.shaders.lazyTargetClears"));
  }

  PackRenderTargets(
      GpuDevice device,
      Map<Integer, BufferSpec> specifications,
      int width,
      int height,
      int shadowSize,
      boolean lazyClears) {
    if (width < 1 || height < 1 || shadowSize < 1)
      throw new IllegalArgumentException("Invalid target size");
    this.device = device;
    this.lazyClears = lazyClears;
    this.specifications = Map.copyOf(specifications);
    this.width = width;
    this.height = height;
    this.shadowSize = shadowSize;
    try {
      for (int i = 0; i < DH_DEPTH; i++)
        depths[i] = allocate("depthtex" + i, GpuFormat.D32_FLOAT, width, height, 1);
      mergedDepth = allocate("merged hand depth", GpuFormat.D32_FLOAT, width, height, 1);
      for (int i = 0; i < shadowDepths.length; i++)
        shadowDepths[i] = allocate("shadowtex" + i, GpuFormat.D32_FLOAT, shadowSize, shadowSize, 1);
    } catch (RuntimeException failure) {
      close();
      throw failure;
    }
  }

  private PackTexture allocate(String name, GpuFormat format, int w, int h, int mips) {
    PackTexture result = new PackTexture(device, name, format, w, h, mips);
    owned.add(result);
    return result;
  }

  public BufferSpec specification(int index) {
    return specifications.getOrDefault(index, DEFAULT);
  }

  public GpuFormat format(int index) {
    return specification(index).format();
  }

  private Pair pair(int index) {
    if (index < 0 || index > 31)
      throw new IllegalArgumentException("Color attachment outside 0..31");
    return colors.computeIfAbsent(
        index,
        key -> {
          BufferSpec spec = specification(key);
          int w = spec.width(width), h = spec.height(height);
          int mips = spec.mipmaps() ? 32 - Integer.numberOfLeadingZeros(Math.max(w, h)) : 1;
          PackTexture a, b;
          try (var writable = storageColors.contains(key) ? dev.cadence.metal.backend.MetalStorage.writableTextures() : null) {
            a = allocate("colortex" + key + " A", spec.format(), w, h, mips);
            b = allocate("colortex" + key + " B", spec.format(), w, h, mips);
          } catch (Exception e) {
            throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
          }
          // Allocation can occur on first program use after beginFrame. Both sides must be defined.
          CommandEncoder encoder = device.createCommandEncoder();
          encoder.clearColorTexture(a.texture, clearColor(key));
          encoder.clearColorTexture(b.texture, clearColor(key));
          return new Pair(a, b);
        });
  }

  private Vector4fc clearColor(int index) {
    Vector4fc explicit = specification(index).clearColor();
    if (explicit != null) return explicit;
    // OptiFine/Iris: colortex1 (gdepth) clears to white, the rest to transparent black. (JOML's new Vector4f() is
    // (0, 0, 0, 1): an alpha of 1 nothing wrote reads as "covered" to packs that test it, Pegasus's water mask made its sky black.)
    return index == 0 ? fogColor : index == 1 ? new Vector4f(1) : new Vector4f(0, 0, 0, 0);
  }

  /** Cadence: the colour buffers made so far whose contents carry from frame to frame (Clear=false: TAA history). */
  public java.util.List<Integer> historyBuffers() {
    java.util.List<Integer> result = new ArrayList<>();
    for (int index : colors.keySet()) if (!specification(index).clear()) result.add(index);
    return result;
  }

  public PackTexture color(int index) {
    return pair(index).read();
  }

  public PackTexture alternate(int index) {
    return pair(index).write();
  }

  public PackTexture depth(int index) {
    if (depths[index] == null) {
      // dhDepthTex0/1: made on first use, defined as cleared (far) from then on
      depths[index] = allocate(index == DH_DEPTH ? "dhDepthTex0" : "dhDepthTex1", GpuFormat.D32_FLOAT, width, height, 1);
      clearDepth(device.createCommandEncoder(), depths[index]);
    }
    return depths[index];
  }

  PackTexture depthMergeTarget() {
    return mergedDepth;
  }

  void commitMergedDepth() {
    // Every consumer asks for the current depth view when binding. Rotate ownership instead of
    // copying the entire merged image back to the old scene image (both are otherwise identical).
    PackTexture previousScene = depths[0];
    depths[0] = mergedDepth;
    mergedDepth = previousScene;
  }

  public PackTexture shadowDepth(int index) {
    return shadowDepths[index];
  }

  // Cadence: shadow color targets some program samples (unread ones are not attached to the shadow pass: nothing reads them)
  private java.util.function.IntPredicate shadowColorRead = index -> true;

  public void setShadowColorRead(java.util.function.IntPredicate read) {
    shadowColorRead = read;
  }

  /** Whether a shadow program's output to this shadow color target is kept. */
  public boolean shadowColorUsed(int index) {
    return index >= 0 && shadowColorRead.test(index);
  }

  /**
   * Whether a shadow pass attaches this color target in this draw-buffer slot: unread ones drop out, except in slot 0
   * (the render pass takes its size from its first color attachment, which must exist when any color slot does).
   */
  public boolean shadowColorAttached(int index, int slot) {
    return index >= 0 && (slot == 0 || shadowColorUsed(index));
  }

  public PackTexture shadowColor(int index) {
    return shadowColors.computeIfAbsent(
        index,
        key -> {
          if (key < 0 || key > 7) throw new IllegalArgumentException("Shadow color outside 0..7");
          ShadowSpec spec = shadowSpecification(key);
          int mips = spec.mipmaps() ? 32 - Integer.numberOfLeadingZeros(shadowSize) : 1;
          PackTexture texture;
          try (var writable = storageShadowColors.contains(key) ? dev.cadence.metal.backend.MetalStorage.writableTextures() : null) {
            texture = allocate("shadowcolor" + key, spec.format(), shadowSize, shadowSize, mips);
          } catch (Exception e) {
            throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
          }
          device.createCommandEncoder().clearColorTexture(texture.texture, spec.clearColor());
          return texture;
        });
  }

  public void beginFrame(Vector4fc fog) {
    beginFrame(fog, true);
  }

  /**
   * Cadence (PackCheck): every target back to its clear value, history buffers (the ones the pack doesn't clear each
   * frame) included, so a run of frames starts from the same state each time.
   */
  public void clearHistory() {
    mipsCurrent.clear();
    CommandEncoder encoder = device.createCommandEncoder();
    for (var entry : colors.entrySet()) {
      Vector4fc clear = clearColor(entry.getKey());
      pendingColors.remove(entry.getValue().read());
      pendingColors.remove(entry.getValue().write());
      encoder.clearColorTexture(entry.getValue().read().texture, clear);
      encoder.clearColorTexture(entry.getValue().write().texture, clear);
    }
    for (PackTexture depth : depths) {
      if (depth == null) continue;
      pendingDepths.remove(depth);
      encoder.clearDepthTexture(depth.texture, 1);
    }
    for (PackTexture depth : shadowDepths) {
      pendingDepths.remove(depth);
      encoder.clearDepthTexture(depth.texture, 1);
    }
    for (var color : shadowColors.entrySet()) {
      pendingColors.remove(color.getValue());
      encoder.clearColorTexture(color.getValue().texture, shadowSpecification(color.getKey()).clearColor());
    }
  }

  /** Cadence: {@code clearShadows} false keeps last frame's shadow map (ShadowReuse). */
  public void beginFrame(Vector4fc fog, boolean clearShadows) {
    fogColor = new Vector4f(fog);
    invalidateFeedback();
    CommandEncoder encoder = device.createCommandEncoder();
    for (var entry : colors.entrySet()) {
      if (!specification(entry.getKey()).clear()) continue;
      // Only the readable side survives into geometry. Screen passes fully overwrite their outputs.
      clearColor(encoder, entry.getValue().read(), clearColor(entry.getKey()));
    }
    for (PackTexture depth : depths) if (depth != null) clearDepth(encoder, depth);
    if (!clearShadows) return;
    for (PackTexture depth : shadowDepths) clearDepth(encoder, depth);
    for (var color : shadowColors.entrySet()) {
      ShadowSpec spec = shadowSpecification(color.getKey());
      if (spec.clear()) clearColor(encoder, color.getValue(), spec.clearColor());
    }
  }

  private void clearColor(CommandEncoder encoder, PackTexture texture, Vector4fc color) {
    mipsCurrent.remove(texture);
    if (lazyClears) pendingColors.put(texture, new Vector4f(color));
    else encoder.clearColorTexture(texture.texture, color);
  }

  private void clearDepth(CommandEncoder encoder, PackTexture texture) {
    if (lazyClears) pendingDepths.add(texture);
    else encoder.clearDepthTexture(texture.texture, 1);
  }

  /**
   * Resolve a sampled/read source before any native pass opens, including before mip generation.
   */
  public void ensureDefined(PackTexture texture) {
    Vector4fc color = pendingColors.remove(texture);
    if (color != null) mipsCurrent.remove(texture);
    if (color != null) device.createCommandEncoder().clearColorTexture(texture.texture, color);
    if (pendingDepths.remove(texture))
      device.createCommandEncoder().clearDepthTexture(texture.texture, 1);
  }

  /** These checks do not allocate a late target or encode work inside an existing scene pass. */
  public boolean needsColorDefinition(int index) {
    Pair pair = colors.get(index);
    return pair == null || pendingColors.containsKey(pair.read());
  }

  public boolean needsDepthDefinition(int index) {
    return depths[index] != null && pendingDepths.contains(depths[index]);
  }

  public boolean needsShadowColorDefinition(int index) {
    PackTexture texture = shadowColors.get(index);
    return texture == null || pendingColors.containsKey(texture);
  }

  public boolean needsShadowDepthDefinition(int index) {
    return pendingDepths.contains(shadowDepths[index]);
  }

  private Optional<Vector4fc> attachmentClear(PackTexture texture) {
    Vector4fc color = pendingColors.remove(texture);
    if (color == null) return Optional.empty();
    // The attachment load action clears only level zero. Keep every other mip defined exactly
    // as the eager all-level clear did; the graph has not proved these levels dead.
    var encoder = device.createCommandEncoder();
    for (int mip = 1; mip < texture.texture.getMipLevels(); mip++) {
      try (var ignored =
          encoder.createRenderPass(
              () -> "Shader pack pending mip clear", texture.level(mip), Optional.of(color))) {
        // A clear-only pass intentionally has no draw.
      }
    }
    return Optional.of(color);
  }

  private OptionalDouble depthAttachmentClear(PackTexture texture) {
    return pendingDepths.remove(texture) ? OptionalDouble.of(1) : OptionalDouble.empty();
  }

  /** Discard temporal images after a camera discontinuity, including targets with Clear=false. */
  public void resetHistory(Vector4fc fog) {
    mipsCurrent.clear();
    fogColor = new Vector4f(fog);
    CommandEncoder encoder = device.createCommandEncoder();
    for (var entry : colors.entrySet()) {
      Vector4fc clear = clearColor(entry.getKey());
      pendingColors.remove(entry.getValue().read());
      pendingColors.remove(entry.getValue().write());
      encoder.clearColorTexture(entry.getValue().read().texture, clear);
      encoder.clearColorTexture(entry.getValue().write().texture, clear);
    }
  }

  public RenderPassDescriptor descriptor(
      String name, List<Integer> outputs, boolean screen, boolean shadow, boolean withDepth) {
    return descriptor(name, outputs, screen, shadow, withDepth, 0);
  }

  public RenderPassDescriptor descriptor(
      String name,
      List<Integer> outputs,
      boolean screen,
      boolean shadow,
      boolean withDepth,
      int depthIndex) {
    if (withDepth && (depthIndex < 0 || depthIndex >= depths.length))
      throw new IllegalArgumentException("Depth attachment outside 0..4");
    RenderPassDescriptor.Builder result = RenderPassDescriptor.builder(() -> name);
    for (int slot = 0; slot < outputs.size(); slot++) {
      int output = outputs.get(slot);
      if (output < 0 || shadow && !shadowColorAttached(output, slot)) result.withUnusedColorAttachment();
      else {
        PackTexture target =
            shadow ? shadowColor(output) : screen ? alternate(output) : color(output);
        mipsCurrent.remove(target);
        result.withColorAttachment(target.level(0), attachmentClear(target));
      }
    }
    if (withDepth) {
      PackTexture target = shadow ? shadowDepths[0] : depth(depthIndex);
      result.withDepthAttachment(target.level(0), depthAttachmentClear(target));
    }
    return result.build();
  }

  public void flip(List<Integer> outputs) {
    // A logical attachment changes sides once even if malformed metadata repeats it.
    outputs.stream().filter(index -> index >= 0).distinct().forEach(index -> pair(index).flip());
  }

  /**
   * Capture once after opaque world geometry, before any transparent or hand draw. depthtex2 must
   * retain this image; depthtex1 can subsequently receive opaque-hand depth independently.
   */
  public void snapshotOpaqueDepth() {
    copyDepth(1);
    copyDepth(2);
  }

  public void copyDepth(int destination) {
    copy(depths[0], depth(destination), width, height);
  }

  /** Cadence: the far terrain's opaque depth (dhDepthTex0) into dhDepthTex1, before the translucent phase. */
  public void copyDistantDepth() {
    copy(depth(DH_DEPTH), depth(DH_DEPTH + 1), width, height);
  }

  public void copyShadowDepth() {
    copy(shadowDepths[0], shadowDepths[1], shadowSize, shadowSize);
  }

  private void copy(PackTexture from, PackTexture to, int w, int h) {
    // All callers copy an entire single-level depth texture. Its queued clear is now dead.
    if (to.texture.getMipLevels() != 1
        || w != to.texture.getWidth(0)
        || h != to.texture.getHeight(0))
      throw new IllegalStateException("Depth snapshot must overwrite the whole destination");
    ensureDefined(from);
    device
        .createCommandEncoder()
        .copyTextureToTexture(from.texture, to.texture, 0, 0, 0, 0, 0, w, h);
    pendingDepths.remove(to);
  }

  // ---- Cadence: feedback snapshots ----
  // A world-geometry program that samples a buffer it is drawing into (Sildur's Vibrant 2.02's water reads colortex4 for
  // its reflections while writing it; Pegasus's water reads depthtex0, its own depth attachment). OpenGL lets that through
  // and the reads see what was there before the draws; on Apple's tile GPUs reading an attachment of the open pass is
  // undefined (black streaks over Sildur's water). Such reads get a copy, made once per phase (opaque, then translucent).
  private final Map<Integer, PackTexture> colorFeedback = new HashMap<>();
  private final Set<Integer> colorFeedbackFresh = new HashSet<>();
  private final PackTexture[] depthFeedback = new PackTexture[5];
  private final boolean[] depthFeedbackFresh = new boolean[5];

  /** Measurement only (self-test ab=nofeedback): feedback reads take the buffer itself, no copy (pictures may be wrong). */
  public static volatile boolean SKIP_FEEDBACK_COPIES;

  /** The copy of color buffer index a program writing it reads, current for this phase. Outside an open pass only. */
  public PackTexture colorFeedback(int index) {
    PackTexture source = color(index);
    if (SKIP_FEEDBACK_COPIES) return source;
    PackTexture copy = colorFeedback.get(index);
    int w = source.texture.getWidth(0), h = source.texture.getHeight(0);
    if (copy == null || copy.texture.getWidth(0) != w || copy.texture.getHeight(0) != h) {
      copy = allocate("colortex" + index + " feedback", source.texture.getFormat(), w, h, 1);
      colorFeedback.put(index, copy);
      colorFeedbackFresh.remove(index);
    }
    if (colorFeedbackFresh.add(index)) {
      ensureDefined(source);
      device.createCommandEncoder().copyTextureToTexture(source.texture, copy.texture, 0, 0, 0, 0, 0, w, h);
    }
    return copy;
  }

  private final Map<Integer, PackTexture> shadowColorFeedback = new HashMap<>();

  /** Cadence: a copy of shadow colour buffer index taken now (outside an open pass), for a shadow composite pass. */
  public void copyShadowColorFeedback(int index) {
    PackTexture source = shadowColor(index);
    int w = source.texture.getWidth(0), h = source.texture.getHeight(0);
    PackTexture copy = shadowColorFeedback.get(index);
    if (copy == null || copy.texture.getWidth(0) != w || copy.texture.getHeight(0) != h) {
      copy = allocate("shadowcolor" + index + " feedback", source.texture.getFormat(), w, h, 1);
      shadowColorFeedback.put(index, copy);
    }
    ensureDefined(source);
    device.createCommandEncoder().copyTextureToTexture(source.texture, copy.texture, 0, 0, 0, 0, 0, w, h);
  }

  /** The copy copyShadowColorFeedback last took of shadow colour buffer index. */
  public PackTexture shadowColorFeedback(int index) {
    PackTexture copy = shadowColorFeedback.get(index);
    return copy != null ? copy : shadowColor(index);
  }

  /** The copy of depth buffer index a program with that depth attachment reads, current for this phase. */
  public PackTexture depthFeedback(int index) {
    if (SKIP_FEEDBACK_COPIES) return depth(index);
    if (depthFeedback[index] == null) depthFeedback[index] = allocate("depthtex" + index + " feedback", GpuFormat.D32_FLOAT, width, height, 1);
    if (!depthFeedbackFresh[index]) {
      depthFeedbackFresh[index] = true;
      copy(depth(index), depthFeedback[index], width, height);
    }
    return depthFeedback[index];
  }

  public boolean colorFeedbackReady(int index) {
    // (no late allocation here: asked while a scene pass is open)
    PackTexture copy = colorFeedback.get(index);
    Pair pair = colors.get(index);
    return copy != null && pair != null && colorFeedbackFresh.contains(index)
        && copy.texture.getWidth(0) == pair.read().texture.getWidth(0)
        && copy.texture.getHeight(0) == pair.read().texture.getHeight(0);
  }

  public boolean depthFeedbackReady(int index) {
    return depthFeedback[index] != null && depthFeedbackFresh[index];
  }

  /** The phase changed (a new frame, or the opaque world is done): the next feedback read copies again. */
  public void invalidateFeedback() {
    colorFeedbackFresh.clear();
    java.util.Arrays.fill(depthFeedbackFresh, false);
  }

  @Override
  public void close() {
    mipsCurrent.clear();
    colorFeedback.clear();
    java.util.Arrays.fill(depthFeedback, null);
    for (int i = owned.size() - 1; i >= 0; i--) owned.get(i).close();
    owned.clear();
    colors.clear();
    shadowColors.clear();
    pendingColors.clear();
    pendingDepths.clear();
  }

  private static final class Pair {
    private final PackTexture a, b;
    private boolean flipped;

    Pair(PackTexture a, PackTexture b) {
      this.a = a;
      this.b = b;
    }

    PackTexture read() {
      return flipped ? b : a;
    }

    PackTexture write() {
      return flipped ? a : b;
    }

    void flip() {
      flipped = !flipped;
    }
  }
}
