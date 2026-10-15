// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Uses precisely vanilla's camera-relative float conversion and frustum-plane classification. */
@Mixin(Frustum.class)
public interface FrustumSceneAccessor {
  @Invoker("cubeInFrustum")
  int scene$classifyCube(
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
}
