package dev.cadence.metal.own;

import org.lwjgl.system.MemoryUtil;

/**
 * Cadence: this frame's dynamic light sources (a held torch, a burning mob, a dropped glowstone...), for the terrain shader. Cadence
 * Shaders' DynamicLights gathers them as the frame's extraction begins; OwnTerrain writes them into every draw's DrawFrame, and the
 * terrain vertex stage raises each vertex's block light to the brightest source's level minus its distance in blocks, as a placed
 * light would light it (through walls too, as LambDynamicLights does). Nothing is rebuilt: a moving light costs nothing on the
 * CPU. Render thread only.
 */
public final class DynamicLightList {
	/** At most this many sources per frame (the nearest to the camera); DrawFrame.lights' size in terrain.metal. */
	public static final int MAX = 16;
	/** DrawFrame's bytes before lightCount (listBase, nowMs, fadeMs, clipOnCpu, clip). */
	static final int OFFSET = 80;
	static final int BYTES = 32 + MAX * 16;

	private static final double[] xyz = new double[MAX * 3];
	private static final float[] level = new float[MAX];
	private static int count;

	private DynamicLightList() {
	}

	/** This frame's sources: world positions (x, y, z per source) and light levels (0-15), {@code n} of them (at most MAX). */
	public static void set(int n, double[] positions, float[] levels) {
		n = Math.min(n, MAX);
		System.arraycopy(positions, 0, xyz, 0, n * 3);
		System.arraycopy(levels, 0, level, 0, n);
		count = n;
	}

	public static int count() {
		return count;
	}

	/** DrawFrame.lightCount and lights at {@code frame}: the sources relative to the camera at (cx, cy, cz), as the vertex stage's positions are. */
	static void write(long frame, double cx, double cy, double cz) {
		int n = count;
		MemoryUtil.memPutInt(frame + OFFSET, n);
		// lightBounds: the box around the sources' reach spheres, as a sphere (its centre and half-diagonal, squared)
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		long at = frame + OFFSET + 32;
		for (int i = 0; i < n; i++, at += 16) {
			double x = xyz[i * 3] - cx, y = xyz[i * 3 + 1] - cy, z = xyz[i * 3 + 2] - cz, r = level[i];
			minX = Math.min(minX, x - r);
			minY = Math.min(minY, y - r);
			minZ = Math.min(minZ, z - r);
			maxX = Math.max(maxX, x + r);
			maxY = Math.max(maxY, y + r);
			maxZ = Math.max(maxZ, z + r);
			MemoryUtil.memPutFloat(at, (float) x);
			MemoryUtil.memPutFloat(at + 4, (float) y);
			MemoryUtil.memPutFloat(at + 8, (float) z);
			MemoryUtil.memPutFloat(at + 12, level[i]);
		}
		double hx = (maxX - minX) / 2, hy = (maxY - minY) / 2, hz = (maxZ - minZ) / 2;
		MemoryUtil.memPutFloat(frame + OFFSET + 16, (float) ((minX + maxX) / 2));
		MemoryUtil.memPutFloat(frame + OFFSET + 20, (float) ((minY + maxY) / 2));
		MemoryUtil.memPutFloat(frame + OFFSET + 24, (float) ((minZ + maxZ) / 2));
		MemoryUtil.memPutFloat(frame + OFFSET + 28, (float) ((hx * hx + hy * hy + hz * hz) * 1.0001 + 1e-3));
	}
}
