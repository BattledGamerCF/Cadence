package dev.cadence.shaders.runtime;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Cadence: the game pipelines (with their hand, shadow and block-entity flags) packs drew with, kept across launches in
 * cadence-cache/pack-pipelines.txt, so the first pack of a session also has its pipelines compiled up front and in parallel
 * (ShaderRuntime.prewarmPipelines), not only packs after it. A line: location|hand|shadow|blockEntity. Lines whose pipeline
 * this game doesn't have (another mod's, an older version's) are skipped. -Dcadence.shaders.pipelineMemory=false: off.
 */
final class PipelineMemory {
  private static final boolean ON = !"false".equals(System.getProperty("cadence.shaders.pipelineMemory"));
  private static final int MAX = 512;
  private static boolean loaded;
  private static volatile boolean dirty;
  private static long nextSave;

  private PipelineMemory() {}

  /** Once a launch: the saved variants added to {@code into}. Any thread. */
  static synchronized void load(Set<ShaderRuntime.Variant> into) {
    if (loaded || !ON) return;
    loaded = true;
    Path file = file();
    if (file == null || !Files.isRegularFile(file)) return;
    try {
      Map<Identifier, RenderPipeline> byLocation = new HashMap<>();
      for (RenderPipeline p : RenderPipelines.requiredPipelines()) byLocation.put(p.getLocation(), p);
      for (RenderPipeline p : RenderPipelines.optionalPipelines()) byLocation.put(p.getLocation(), p);
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String[] f = line.split("\\|");
        if (f.length != 4 || into.size() >= MAX) continue;
        RenderPipeline original = resolve(f[0], byLocation);
        if (original != null)
          into.add(new ShaderRuntime.Variant(original, f[1].equals("1"), f[2].equals("1"), f[3].equals("1")));
      }
    } catch (Exception e) {
      // a damaged list is only a slower first pack: started over
    }
  }

  private static @Nullable RenderPipeline resolve(String location, Map<Identifier, RenderPipeline> byLocation) {
    Identifier id = Identifier.tryParse(location);
    if (id == null) return null;
    RenderPipeline direct = byLocation.get(id);
    if (direct != null) return direct;
    // the shader terrain's own versions of the game's terrain pipelines (TerrainShaderGeometry: geometry/<path>[/compact44])
    String path = id.getPath();
    if (!id.getNamespace().equals("cadence_shaders")) return null;
    // block entities and moving blocks drawn with normals (DynamicBlockGeometry: dynamic_block/<path>)
    if (path.startsWith("dynamic_block/")) {
      RenderPipeline vanilla = byLocation.get(Identifier.withDefaultNamespace(path.substring("dynamic_block/".length())));
      return vanilla == null ? null : dev.cadence.shaders.geometry.DynamicBlockGeometry.extended(vanilla);
    }
    if (!path.startsWith("geometry/")) return null;
    boolean compact = path.endsWith("/compact44");
    String base = path.substring("geometry/".length(), compact ? path.length() - "/compact44".length() : path.length());
    RenderPipeline vanilla = byLocation.get(Identifier.withDefaultNamespace(base));
    return vanilla == null ? null : TerrainShaderGeometry.pipeline(vanilla, compact);
  }

  /** A variant was compiled that the list may not have. */
  static void changed() {
    dirty = true;
  }

  /** Render thread, each frame: the list written (on a background thread) at most every 30 s while it changes. */
  static void maybeSave(Set<ShaderRuntime.Variant> variants, boolean now) {
    if (!dirty || !ON) return;
    long t = System.nanoTime();
    if (!now && t < nextSave) return;
    nextSave = t + 30_000_000_000L;
    dirty = false;
    List<String> lines = new ArrayList<>();
    for (ShaderRuntime.Variant v : variants)
      lines.add(v.original().getLocation() + "|" + (v.hand() ? 1 : 0) + "|" + (v.shadow() ? 1 : 0) + "|" + (v.blockEntity() ? 1 : 0));
    Path file = file();
    if (file == null) return;
    Thread.ofPlatform().daemon().name("cadence-pipeline-memory").start(() -> {
      try {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception e) {
        // next time
      }
    });
  }

  private static @Nullable Path file() {
    try {
      Path d = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("cadence-cache");
      Files.createDirectories(d);
      return d.resolve("pack-pipelines.txt");
    } catch (Throwable e) {
      return null;
    }
  }
}
