// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4d;

/**
 * Conservative convex receiver volume swept toward a directional light. Coordinates are relative
 * to the render camera; this avoids losing section precision at large world coordinates.
 */
final class ShadowCasterVolume {
  private static final ShadowCasterVolume UNBOUNDED = new ShadowCasterVolume(List.of(), 0);
  private final List<Plane> planes;
  private final double padding;

  private record Point(double x, double y, double z) {}

  private record Plane(double x, double y, double z, double d) {
    double dot(Point p) {
      return x * p.x + y * p.y + z * p.z;
    }
  }

  private ShadowCasterVolume(List<Plane> planes, double padding) {
    this.planes = List.copyOf(planes);
    this.padding = padding;
  }

  /**
   * The receiver hull includes the eye as well as both frustum faces: volumetric samples and hand
   * receivers may lie ahead of the eye but before the near plane. Camera effects belong in view.
   * Invalid/degenerate inputs deliberately disable rejection for this frame.
   */
  static ShadowCasterVolume create(
      Matrix4fc projection, Matrix4fc view, Vector3fc towardLight, double padding) {
    if (!Double.isFinite(padding) || padding < 0) return UNBOUNDED;
    double lx = towardLight.x(), ly = towardLight.y(), lz = towardLight.z();
    double lightLength = Math.sqrt(lx * lx + ly * ly + lz * lz);
    if (!Double.isFinite(lightLength) || lightLength < 1.0e-9) return UNBOUNDED;
    Point light = new Point(lx / lightLength, ly / lightLength, lz / lightLength);
    Matrix4d inverse = new Matrix4d(projection).mul(new Matrix4d(view)).invert();
    List<Point> points = new ArrayList<>(9);
    for (int z = -1; z <= 1; z += 2) {
      for (int y = -1; y <= 1; y += 2) {
        for (int x = -1; x <= 1; x += 2) {
          Vector4d p = inverse.transform(new Vector4d(x, y, z, 1));
          points.add(new Point(p.x / p.w, p.y / p.w, p.z / p.w));
        }
      }
    }
    Vector4d eye = new Matrix4d(view).invert().transform(new Vector4d(0, 0, 0, 1));
    points.add(new Point(eye.x / eye.w, eye.y / eye.w, eye.z / eye.w));
    double extent = 1;
    for (Point p : points) {
      double magnitude = Math.max(Math.max(Math.abs(p.x), Math.abs(p.y)), Math.abs(p.z));
      if (!Double.isFinite(magnitude) || magnitude > 1.0e7) return UNBOUNDED;
      extent = Math.max(extent, magnitude);
    }
    double tolerance = extent * 1.0e-8;
    List<Plane> planes = new ArrayList<>(12);
    for (int i = 0; i < points.size(); i++) {
      Point a = points.get(i);
      for (int j = i + 1; j < points.size(); j++) {
        Point edge = subtract(points.get(j), a);
        // Silhouette planes contain a hull edge and are parallel to the light. Trying every
        // pair avoids relying on near-plane/eye topology; non-supporting planes are discarded.
        addSupportingPlane(planes, points, a, cross(edge, light), light, true, tolerance);
        for (int k = j + 1; k < points.size(); k++) {
          // Original hull faces survive extrusion only if moving toward the light stays inside.
          addSupportingPlane(
              planes, points, a, cross(edge, subtract(points.get(k), a)), light, false, tolerance);
        }
      }
    }
    return planes.isEmpty() ? UNBOUNDED : new ShadowCasterVolume(planes, padding);
  }

  private static Point subtract(Point a, Point b) {
    return new Point(a.x - b.x, a.y - b.y, a.z - b.z);
  }

  private static Point cross(Point a, Point b) {
    return new Point(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
  }

  private static void addSupportingPlane(
      List<Plane> planes,
      List<Point> points,
      Point origin,
      Point normal,
      Point light,
      boolean parallelToLight,
      double tolerance) {
    double length = Math.sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z);
    if (!Double.isFinite(length) || length < 1.0e-12) return;
    double x = normal.x / length, y = normal.y / length, z = normal.z / length;
    double originDot = x * origin.x + y * origin.y + z * origin.z;
    double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
    for (Point p : points) {
      double dot = x * p.x + y * p.y + z * p.z - originDot;
      low = Math.min(low, dot);
      high = Math.max(high, dot);
    }
    if (low < -tolerance && high > tolerance) return;
    if (high <= tolerance) {
      x = -x;
      y = -y;
      z = -z;
    }
    // A slightly negative facing value must not reject distant lightward casters. Discarding a
    // nearly parallel original face is safe; its silhouette plane supplies the exact boundary.
    if (!parallelToLight && x * light.x + y * light.y + z * light.z <= 1.0e-10) return;
    double minDot = Double.POSITIVE_INFINITY;
    for (Point p : points) minDot = Math.min(minDot, x * p.x + y * p.y + z * p.z);
    Plane plane = new Plane(x, y, z, -minDot + tolerance);
    for (Plane previous : planes) {
      if (plane.x * previous.x + plane.y * previous.y + plane.z * previous.z > 1 - 1.0e-10
          && Math.abs(plane.d - previous.d) < tolerance * 4) return;
    }
    planes.add(plane);
  }

  int planeCount() {
    return planes.size();
  }

  /** Separating-plane AABB test; false positives are allowed, false negatives are not. */
  boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    for (Plane plane : planes) {
      double x = plane.x >= 0 ? maxX : minX;
      double y = plane.y >= 0 ? maxY : minY;
      double z = plane.z >= 0 ? maxZ : minZ;
      if (plane.x * x + plane.y * y + plane.z * z + plane.d < -padding) return false;
    }
    return true;
  }
}
