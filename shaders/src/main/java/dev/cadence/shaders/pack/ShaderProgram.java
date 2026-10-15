// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.pack;

import java.util.Map;

/** Paths are relative to the pack's shaders directory, never paths on the host filesystem. */
public record ShaderProgram(String name, String directory, Map<ShaderStage, String> paths) {
  public ShaderProgram {
    paths = Map.copyOf(paths);
  }

  public String identifier() {
    return directory.isEmpty() ? name : directory + "/" + name;
  }
}
