// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

/** Cache storage belongs to the mesh, never to a global identity history. */
public interface PreparedMeshDraws {
  MeshDrawCache scene$drawCache();

  /** Allocation changes need not allocate a cache for a mesh that has never been drawn. */
  void scene$invalidateDrawAllocations();
}
