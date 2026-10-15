// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.compile;

import java.util.List;

/** Backend-neutral GLSL and the complete resource contract needed to execute it. */
public record TranslatedProgram(
    String label,
    String vertexSource,
    String fragmentSource,
    UniformLayout uniforms,
    List<Sampler> samplers,
    List<Attribute> attributes,
    List<Integer> drawBuffers,
    String preprocessedVertex,
    String preprocessedFragment,
    List<Storage> storage) {
  public TranslatedProgram {
    samplers = List.copyOf(samplers);
    attributes = List.copyOf(attributes);
    drawBuffers = List.copyOf(drawBuffers);
    storage = List.copyOf(storage);
  }

  public TranslatedProgram(
      String label,
      String vertexSource,
      String fragmentSource,
      UniformLayout uniforms,
      List<Sampler> samplers,
      List<Attribute> attributes,
      List<Integer> drawBuffers,
      String preprocessedVertex,
      String preprocessedFragment) {
    this(label, vertexSource, fragmentSource, uniforms, samplers, attributes, drawBuffers, preprocessedVertex,
        preprocessedFragment, List.of());
  }

  /**
   * Cadence: a resource outside the game's binding model, declared in descriptor set 1 at {@code binding}: a pack's image
   * (load/store), a texture sampled through a set-1 sampler (3D textures, custom images), or a buffer object (its index in
   * {@code bufferIndex}). name: the image or sampler name the pack uses.
   */
  public record Storage(String name, Kind kind, String type, int binding, int bufferIndex) {
    public enum Kind {
      IMAGE,
      SAMPLED,
      BUFFER
    }
  }

  /** Names remain the pack's resource names, including aliases such as texture and gaux1. */
  public record Sampler(String name, String type) {
    public boolean comparison() {
      return type.endsWith("Shadow");
    }
  }

  /** legacyName identifies the incoming pack attribute; shaderName is its lowered GLSL name. */
  public record Attribute(String legacyName, String shaderName, String type, int location) {}
}
