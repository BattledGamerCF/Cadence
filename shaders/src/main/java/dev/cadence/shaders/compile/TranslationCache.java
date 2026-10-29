package dev.cadence.shaders.compile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Cadence: a shader pack's programs are translated once (GLSL preprocessing and lowering to the game's GLSL), not each time
 * shaders are turned on. Two layers: memory (turning shaders off and on again in one session is instant) and disk under
 * {@code <game dir>/cadence-cache/translated/} (a later launch skips the work too).
 *
 * <p>The key is everything a translation depends on: the program's source text as the pack and its options make it, the label,
 * the vertex format adapter, the alpha test, the shadow comparison mode, and a fingerprint of the translator's own code (its
 * class files) and settings, so a Cadence update can never reuse an old translation. -Dcadence.shaders.translationCache=false
 * turns it off; =verify translates every hit again and logs any difference.
 */
public final class TranslationCache {
  private static final String MODE = System.getProperty("cadence.shaders.translationCache", "true");
  static final boolean ON = !MODE.equals("false");
  private static final boolean VERIFY = MODE.equals("verify");
  /** Memory layer budget, in characters of translated source (about as many bytes). */
  private static final long MEMORY_BUDGET = Long.getLong("cadence.shaders.translationCacheMemory", 48L << 20);
  /** Disk layer budget; the least recently used entries go first. */
  private static final long DISK_BUDGET = Long.getLong("cadence.shaders.translationCacheDisk", 256L << 20);
  private static final int FORMAT = 2;

  private static final Map<String, TranslatedProgram> MEMORY = new LinkedHashMap<>(64, 0.75f, true);
  private static long memoryChars;
  private static volatile @Nullable String salt;
  private static volatile @Nullable Path directory;
  public static volatile long hits, diskHits, misses, mismatches, savedNanos;

  private TranslationCache() {
  }

  /** The cached translation for these inputs, else {@code translate}'s result (then cached). */
  static TranslatedProgram get(Object[] inputs, Supplier<TranslatedProgram> translate) {
    if (!ON) return translate.get();
    String key = key(inputs);
    TranslatedProgram hit;
    synchronized (MEMORY) {
      hit = MEMORY.get(key);
    }
    boolean fromDisk = false;
    if (hit == null) {
      hit = read(key);
      fromDisk = hit != null;
    }
    if (hit != null) {
      if (VERIFY) {
        TranslatedProgram fresh = translate.get();
        if (!fresh.equals(hit)) {
          mismatches++;
          LoggerFactory.getLogger("cadence_shaders").warn("Translation cache mismatch for {}", fresh.label());
          hit = fresh;
        }
      }
      hits++;
      if (fromDisk) {
        diskHits++;
        remember(key, hit);
      }
      return hit;
    }
    long t0 = System.nanoTime();
    TranslatedProgram fresh = translate.get();
    savedNanos += System.nanoTime() - t0;  // what a later hit saves
    misses++;
    remember(key, fresh);
    write(key, fresh);
    return fresh;
  }

  private static void remember(String key, TranslatedProgram program) {
    synchronized (MEMORY) {
      if (MEMORY.put(key, program) == null) memoryChars += size(program);
      var it = MEMORY.entrySet().iterator();
      while (memoryChars > MEMORY_BUDGET && it.hasNext()) {
        var eldest = it.next();
        if (eldest.getKey().equals(key)) break;
        memoryChars -= size(eldest.getValue());
        it.remove();
      }
    }
  }

  private static long size(TranslatedProgram p) {
    return (long) p.vertexSource().length() + p.fragmentSource().length() + p.preprocessedVertex().length()
        + p.preprocessedFragment().length();
  }

  /** Drops the memory layer (the disk layer stays). */
  public static void clearMemory() {
    synchronized (MEMORY) {
      MEMORY.clear();
      memoryChars = 0;
    }
  }

  private static String key(Object[] inputs) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(salt().getBytes(StandardCharsets.UTF_8));
      for (Object input : inputs) {
        byte[] bytes = String.valueOf(input).getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new IllegalStateException("translation cache key", e);
    }
  }

  /** The translator's code and settings: any change makes every earlier entry unreachable. */
  private static String salt() {
    String s = salt;
    if (s != null) return s;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Class<?> c : List.of(ShaderCompatibilityCompiler.class, UniformLayout.class, UniformDefaults.class,
          EarlyAlphaDemotion.class, UniformInitializerLifting.class, TranslatedProgram.class, TranslationCache.class)) {
        try (InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
          if (in != null) digest.update(in.readAllBytes());
        }
      }
      digest.update(("format " + FORMAT + "|lift " + Boolean.getBoolean("cadence.shaders.liftUniformInitializers")
          + "|cdepth " + System.getProperty("cadence.shaders.conservativeDepth", "")
          + "|lwjgl " + org.lwjgl.Version.getVersion()).getBytes(StandardCharsets.UTF_8));
      return salt = HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new IllegalStateException("translation cache salt", e);
    }
  }

  private static @Nullable Path directory() {
    Path d = directory;
    if (d != null) return d;
    try {
      String override = System.getProperty("cadence.shaders.translationCacheDir");
      d = override != null ? Path.of(override)
          : net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("cadence-cache").resolve("translated");
      Files.createDirectories(d);
      prune(d);
      return directory = d;
    } catch (Throwable e) {  // no game directory (a tool run without Fabric) or no disk: memory only
      return null;
    }
  }

  /** Keeps the directory under its budget: oldest-used entries go first. */
  private static void prune(Path d) {
    // (temporary files of writes the game was killed during: gone after an hour)
    try (var files = Files.list(d)) {
      long hourAgo = System.currentTimeMillis() - 3_600_000L;
      for (Path p : (Iterable<Path>) files::iterator)
        if (p.getFileName().toString().endsWith(".tmp") && Files.getLastModifiedTime(p).toMillis() < hourAgo) Files.deleteIfExists(p);
    } catch (IOException ignored) {
    }
    try (var files = Files.list(d)) {
      List<Path> all = new ArrayList<>(files.filter(p -> p.toString().endsWith(".gz")).toList());
      long total = 0;
      Map<Path, Long> sizes = new LinkedHashMap<>();
      Map<Path, Long> used = new LinkedHashMap<>();
      for (Path p : all) {
        long size = Files.size(p);
        sizes.put(p, size);
        used.put(p, Files.getLastModifiedTime(p).toMillis());
        total += size;
      }
      if (total <= DISK_BUDGET) return;
      all.sort(java.util.Comparator.comparingLong(used::get));
      for (Path p : all) {
        if (total <= DISK_BUDGET * 3 / 4) break;
        Files.deleteIfExists(p);
        total -= sizes.get(p);
      }
    } catch (IOException e) {
      // a cache that can't be pruned is only bigger
    }
  }

  private static @Nullable TranslatedProgram read(String key) {
    Path d = directory();
    if (d == null) return null;
    Path file = d.resolve(key + ".gz");
    if (!Files.isRegularFile(file)) return null;
    try {
      byte[] bytes = Files.readAllBytes(file);
      try {
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
      } catch (IOException ignored) {
        // used-time only orders pruning
      }
      try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
        if (in.readInt() != FORMAT) return null;
        String label = string(in), vertex = string(in), fragment = string(in);
        int fieldCount = in.readInt();
        List<UniformLayout.Field> fields = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++)
          fields.add(new UniformLayout.Field(string(in), string(in), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt()));
        int byteSize = in.readInt();
        int defaultCount = in.readInt();
        Map<String, float[]> defaults = new LinkedHashMap<>();
        for (int i = 0; i < defaultCount; i++) {
          String name = string(in);
          float[] value = new float[in.readInt()];
          for (int j = 0; j < value.length; j++) value[j] = in.readFloat();
          defaults.put(name, value);
        }
        int samplerCount = in.readInt();
        List<TranslatedProgram.Sampler> samplers = new ArrayList<>(samplerCount);
        for (int i = 0; i < samplerCount; i++) samplers.add(new TranslatedProgram.Sampler(string(in), string(in)));
        int attributeCount = in.readInt();
        List<TranslatedProgram.Attribute> attributes = new ArrayList<>(attributeCount);
        for (int i = 0; i < attributeCount; i++)
          attributes.add(new TranslatedProgram.Attribute(string(in), string(in), string(in), in.readInt()));
        int bufferCount = in.readInt();
        List<Integer> drawBuffers = new ArrayList<>(bufferCount);
        for (int i = 0; i < bufferCount; i++) drawBuffers.add(in.readInt());
        String preprocessedVertex = string(in), preprocessedFragment = string(in);
        int storageCount = in.readInt();
        List<TranslatedProgram.Storage> storage = new ArrayList<>(storageCount);
        for (int i = 0; i < storageCount; i++)
          storage.add(new TranslatedProgram.Storage(string(in), TranslatedProgram.Storage.Kind.valueOf(string(in)), string(in),
              in.readInt(), in.readInt()));
        return new TranslatedProgram(label, vertex, fragment, new UniformLayout(fields, byteSize, defaults), samplers, attributes,
            drawBuffers, preprocessedVertex, preprocessedFragment, storage);
      }
    } catch (IOException | RuntimeException e) {  // unreadable: translate again (the write replaces it)
      return null;
    }
  }

  private static void write(String key, TranslatedProgram p) {
    Path d = directory();
    if (d == null) return;
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
        out.writeInt(FORMAT);
        string(out, p.label());
        string(out, p.vertexSource());
        string(out, p.fragmentSource());
        out.writeInt(p.uniforms().fields().size());
        for (UniformLayout.Field f : p.uniforms().fields()) {
          string(out, f.name());
          string(out, f.type());
          out.writeInt(f.arrayLength());
          out.writeInt(f.offset());
          out.writeInt(f.byteSize());
          out.writeInt(f.arrayStride());
          out.writeInt(f.matrixStride());
        }
        out.writeInt(p.uniforms().byteSize());
        out.writeInt(p.uniforms().defaults().size());
        for (var e : p.uniforms().defaults().entrySet()) {
          string(out, e.getKey());
          out.writeInt(e.getValue().length);
          for (float v : e.getValue()) out.writeFloat(v);
        }
        out.writeInt(p.samplers().size());
        for (var s : p.samplers()) {
          string(out, s.name());
          string(out, s.type());
        }
        out.writeInt(p.attributes().size());
        for (var a : p.attributes()) {
          string(out, a.legacyName());
          string(out, a.shaderName());
          string(out, a.type());
          out.writeInt(a.location());
        }
        out.writeInt(p.drawBuffers().size());
        for (int b : p.drawBuffers()) out.writeInt(b);
        string(out, p.preprocessedVertex());
        string(out, p.preprocessedFragment());
        out.writeInt(p.storage().size());
        for (var r : p.storage()) {
          string(out, r.name());
          string(out, r.kind().name());
          string(out, r.type());
          out.writeInt(r.binding());
          out.writeInt(r.bufferIndex());
        }
      }
      Path tmp = Files.createTempFile(d, key, ".tmp");
      try {
        Files.write(tmp, bytes.toByteArray());
        Files.move(tmp, d.resolve(key + ".gz"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(tmp);  // (a write that failed half way: not left behind)
      }
    } catch (IOException e) {
      // a cache that can't be written is just a slower next time
    }
  }

  private static void string(DataOutputStream out, String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    out.writeInt(b.length);
    out.write(b);
  }

  private static String string(DataInputStream in) throws IOException {
    byte[] b = new byte[in.readInt()];
    in.readFully(b);
    return new String(b, StandardCharsets.UTF_8);
  }
}
