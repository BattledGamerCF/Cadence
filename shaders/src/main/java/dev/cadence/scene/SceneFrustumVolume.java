// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene;

import dev.cadence.scene.mixin.FrustumSceneAccessor;
import java.util.Objects;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;

/** A vanilla frustum with optional outward padding applied equally to cells and sections. */
public final class SceneFrustumVolume implements SceneVolume {
  private final Frustum frustum;
  private final double padding;
  private final CubeClassifier classifier;

  public SceneFrustumVolume(Frustum frustum, double padding) {
    this(
        frustum,
        padding,
        frustum.getClass() == Frustum.class && frustum instanceof FrustumSceneAccessor accessor
            ? accessor::scene$classifyCube
            : null);
  }

  // Package-local seam permits comparing the real vanilla classifier without booting Mixins/GPU.
  SceneFrustumVolume(Frustum frustum, double padding, CubeClassifier classifier) {
    this.frustum = Objects.requireNonNull(frustum, "frustum");
    if (!Double.isFinite(padding) || padding < 0) {
      throw new IllegalArgumentException("Scene frustum padding must be finite and nonnegative");
    }
    this.padding = padding;
    this.classifier = classifier;
  }

  @Override
  public boolean intersects(AABB bounds) {
    // Boundary sections and custom Frustum subclasses keep the exact existing predicate.
    return frustum.isVisible(padding == 0 ? bounds : bounds.inflate(padding));
  }

  @Override
  public SceneVolume workerSnapshot() {
    // Never erase a custom subclass's visibility semantics or share mutable camera coordinates.
    return frustum.getClass() == Frustum.class
        ? new SceneFrustumVolume(new Frustum(frustum), padding)
        : null;
  }

  @Override
  public Classification classify(AABB bounds) {
    if (classifier == null) return SceneVolume.super.classify(bounds);
    int result =
        classifier.classify(
            bounds.minX - padding,
            bounds.minY - padding,
            bounds.minZ - padding,
            bounds.maxX + padding,
            bounds.maxY + padding,
            bounds.maxZ + padding);
    // Padding a containing cell preserves containment of every equally padded child. Only
    // vanilla's full-inside result proves that individual section predicates can be omitted.
    if (result == FrustumIntersection.INSIDE) return Classification.INSIDE;
    if (result == FrustumIntersection.INTERSECT) return Classification.INTERSECT;
    return Classification.OUTSIDE;
  }

  @FunctionalInterface
  interface CubeClassifier {
    int classify(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
  }
}
