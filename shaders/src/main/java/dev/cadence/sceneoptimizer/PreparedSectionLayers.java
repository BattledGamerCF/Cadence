// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** Borrowed read-only layer array, preserving vanilla enum order. */
public interface PreparedSectionLayers {
  ChunkSectionLayer[] scene$drawLayers();
}
