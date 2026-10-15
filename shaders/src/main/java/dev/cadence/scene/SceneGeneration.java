// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene;

/**
 * Versions of world resources and pack-defined geometry requirements, owned by the render thread.
 */
public record SceneGeneration(long world, long materials) {}
