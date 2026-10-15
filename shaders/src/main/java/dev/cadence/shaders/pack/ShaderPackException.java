// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.pack;

import java.io.IOException;

/** A malformed or unsupported pack, with a message suitable for the pack selection screen. */
public final class ShaderPackException extends IOException {
  public ShaderPackException(String message) {
    super(message);
  }

  public ShaderPackException(String message, Throwable cause) {
    super(message, cause);
  }
}
