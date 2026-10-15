// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.pack;

/** Identifies stages without implying that the selected backend can execute every stage. */
public enum ShaderStage {
  VERTEX("vsh"),
  FRAGMENT("fsh"),
  COMPUTE("csh"),
  GEOMETRY("gsh"),
  TESS_CONTROL("tcs"),
  TESS_EVALUATION("tes");

  private final String extension;

  ShaderStage(String extension) {
    this.extension = extension;
  }

  public String extension() {
    return extension;
  }
}
