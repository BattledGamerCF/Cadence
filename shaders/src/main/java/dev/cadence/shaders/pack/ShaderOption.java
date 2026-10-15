// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.pack;

import java.util.ArrayList;
import java.util.List;

/** Pack-declared option; switch values are represented as "true" and "false". */
public record ShaderOption(
    String name, Kind kind, String defaultValue, List<String> allowedValues) {
  public enum Kind {
    SWITCH,
    DEFINE,
    CONSTANT
  }

  public ShaderOption {
    List<String> choices = new ArrayList<>(allowedValues);
    if (!choices.contains(defaultValue)) choices.addFirst(defaultValue);
    allowedValues = List.copyOf(choices);
  }

  public void validate(String value) throws ShaderPackException {
    if (!allowedValues.contains(value)) {
      throw new ShaderPackException(
          "Invalid value '"
              + value
              + "' for shader option "
              + name
              + "; expected "
              + allowedValues);
    }
  }
}
