package dev.cadence.shaders.compile;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * Cadence: the game's GLSL to SPIR-V compile of a shader pack's program, kept on disk under
 * {@code <game dir>/cadence-cache/spirv/} (the next step, SPIR-V to Metal source, has its own cache in the Metal backend, and
 * macOS keeps the compiled Metal code). Only the shader loader's own pipelines ("cadence_shaders:" names). The key: the
 * pipeline name (it picks the compile options, ShadercFragmentOptimization), the source, the stage, the defines, and the
 * code that compiles (the game's compiler class, ours, LWJGL's shaderc). -Dcadence.shaders.spirvCache=false turns it off.
 */
public final class SpirvCache {
  private static final boolean ON = !"false".equals(System.getProperty("cadence.shaders.spirvCache"));
  private static volatile @Nullable String salt;
  private static volatile @Nullable Path directory;
  public static volatile long hits, misses;

  private SpirvCache() {
  }

  public static boolean applies(String name) {
    return ON && name.startsWith("cadence_shaders:");
  }

  public static String key(String name, String source, Object stage, String defines) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : new String[] {salt(), name, source, String.valueOf(stage), defines}) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new IllegalStateException("SPIR-V cache key", e);
    }
  }

  /** The cached SPIR-V in a buffer from MemoryUtil.memAlloc (the module that takes it frees it), or null. */
  public static @Nullable ByteBuffer read(String key) {
    Path d = directory();
    if (d == null) return null;
    try {
      byte[] bytes = Files.readAllBytes(d.resolve(key + ".spv"));
      if (bytes.length < 20 || bytes.length % 4 != 0) return null;
      ByteBuffer out = org.lwjgl.system.MemoryUtil.memAlloc(bytes.length);
      out.put(bytes).flip();
      hits++;
      return out;
    } catch (java.io.IOException missing) {
      return null;
    }
  }

  public static void write(String key, ByteBuffer spv) {
    misses++;
    Path d = directory();
    if (d == null) return;
    try {
      byte[] bytes = new byte[spv.remaining()];
      spv.duplicate().get(bytes);
      Path tmp = Files.createTempFile(d, key, ".tmp");
      Files.write(tmp, bytes);
      Files.move(tmp, d.resolve(key + ".spv"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (java.io.IOException e) {
      // a cache that can't be written is just a slower next time
    }
  }

  private static String salt() throws Exception {
    String s = salt;
    if (s != null) return s;
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (Class<?> c : new Class<?>[] {com.mojang.renderpearl.frontend.shaders.GlslCompiler.class, ShadercFragmentOptimization.class,
        SpirvCache.class}) {
      try (InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
        if (in != null) digest.update(in.readAllBytes());
      }
    }
    digest.update(org.lwjgl.Version.getVersion().getBytes(StandardCharsets.UTF_8));
    return salt = HexFormat.of().formatHex(digest.digest());
  }

  private static @Nullable Path directory() {
    Path d = directory;
    if (d != null) return d;
    try {
      d = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("cadence-cache").resolve("spirv");
      Files.createDirectories(d);
      return directory = d;
    } catch (Throwable e) {
      return null;
    }
  }
}
