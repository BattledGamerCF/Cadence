// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders;

import dev.cadence.shaders.pack.ShaderPack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** A selected pack is external content. Neither its source nor its settings enter the mod JAR. */
public record ShaderLoaderConfig(Path pack, Map<String, String> options, String profile) {
  public ShaderLoaderConfig {
    options = Map.copyOf(options);
  }

  public static ShaderLoaderConfig load(Path gameDirectory, Path configDirectory)
      throws IOException {
    Path packs = gameDirectory.resolve("shaderpacks");
    Files.createDirectories(packs);
    Properties properties = new Properties();
    Path config = dev.cadence.metal.Legacy.readable(configDirectory, dev.cadence.metal.Legacy.SHADERS_CONFIG, dev.cadence.metal.Legacy.SHADERS_CONFIG_OLD);
    if (Files.isRegularFile(config)) {
      try (var input = Files.newBufferedReader(config)) {
        properties.load(input);
      }
    }
    String selected =
        System.getProperty("cadence.shaders.pack", properties.getProperty("pack", "")).trim();
    // Cadence: the profile chosen for this pack (profile.<pack>=), else the older shared one (ignored if the pack lacks it)
    String profile =
        System.getProperty("cadence.shaders.profile",
                properties.getProperty("profile." + selected, properties.getProperty("profile", "")))
            .trim();
    Map<String, String> options = new TreeMap<>();
    for (String key : properties.stringPropertyNames()) {
      if (key.startsWith("option."))
        options.put(key.substring(7), properties.getProperty(key).trim());
    }
    // Cadence: the pack's own settings (Shader Pack Settings), in shaderpacks/<pack>.txt as Iris keeps them, so settings made
    // in Iris carry over; they win over the older global option.* keys
    if (!selected.isEmpty()) {
      Path own = packs.resolve(Path.of(selected).getFileName().toString() + ".txt");
      if (Files.isRegularFile(own)) {
        Properties p = new Properties();
        try (var input = Files.newBufferedReader(own)) {
          p.load(input);
        }
        for (String key : p.stringPropertyNames()) options.put(key, p.getProperty(key).trim());
      }
    }
    for (String key : System.getProperties().stringPropertyNames()) {
      if (key.startsWith("cadence.shaders.option."))
        options.put(key.substring("cadence.shaders.option.".length()), System.getProperty(key));
    }
    Path location = selected.isEmpty() ? null : Path.of(selected);
    if (location != null && !location.isAbsolute()) location = packs.resolve(location).normalize();
    return new ShaderLoaderConfig(location, options, profile);
  }

  public Map<String, String> effectiveOptions(ShaderPack snapshot) throws IOException {
    Map<String, String> result = new TreeMap<>();
    if (!profile.isEmpty()) result.putAll(snapshot.profileOverrides(profile));
    result.putAll(options);
    snapshot.optionValues(result);
    return Map.copyOf(result);
  }
}
