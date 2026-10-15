package dev.cadence.shaders.cadence.facecull;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Cadence: a section layer's quads grouped by the way they face, so groups facing away from the viewer are not drawn.
 *
 * <p>At build time (worker thread) the opaque/cutout layers' quads are reordered — a stable partition — into seven groups:
 * not axis-aligned, then +Y, -Y, +X, -X, +Z, -Z. Quads in one group keep their relative order (a grass side and its
 * overlay, same facing, still draw in the same order). For each axis group the plane nearest to where it could be seen
 * from is kept: an +X quad at x = p is front-facing only from x > p, so the +X group can be seen only from x > min p.
 * When draws are built, a group the viewer can't be in front of is left out: every one of its quads would be removed by
 * the GPU's back-face culling anyway (only done for layers whose pipeline culls back faces), but now before their
 * vertices are processed. The pixels are the same.
 */
public final class FaceGroups {
	public static final int UNASSIGNED = 0, UP = 1, DOWN = 2, EAST = 3, WEST = 4, SOUTH = 5, NORTH = 6, COUNT = 7;

	/** Quad index where each group starts (start[COUNT] = total quads). */
	public final int[] start = new int[COUNT + 1];
	/** For axis groups: the plane coordinate (section-relative) from which the group starts being visible. */
	public final float[] plane = new float[COUNT];

	private FaceGroups() {
	}

	/** Reorders the quads of a built layer in place and returns its groups; null when there is nothing to gain. */
	public static FaceGroups reorder(ByteBuffer vertices, int vertexSize, int vertexCount) {
		int quads = vertexCount / 4;
		if (quads < 8 || vertexCount % 4 != 0) return null;
		int quadBytes = vertexSize * 4;
		long base = MemoryUtil.memAddress(vertices);
		byte[] group = new byte[quads];
		int[] counts = new int[COUNT];
		FaceGroups g = new FaceGroups();
		for (int i = 0; i < COUNT; i++) g.plane[i] = i == UP || i == EAST || i == SOUTH ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
		for (int q = 0; q < quads; q++) {
			long a = base + (long) q * quadBytes, b = a + vertexSize, c = b + vertexSize;
			float ax = MemoryUtil.memGetFloat(a), ay = MemoryUtil.memGetFloat(a + 4), az = MemoryUtil.memGetFloat(a + 8);
			float e1x = MemoryUtil.memGetFloat(b) - ax, e1y = MemoryUtil.memGetFloat(b + 4) - ay, e1z = MemoryUtil.memGetFloat(b + 8) - az;
			float e2x = MemoryUtil.memGetFloat(c) - ax, e2y = MemoryUtil.memGetFloat(c + 4) - ay, e2z = MemoryUtil.memGetFloat(c + 8) - az;
			float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			int k = UNASSIGNED;
			if (len > 1e-12f) {
				nx /= len;
				ny /= len;
				nz /= len;
				// axis-aligned (and planar: the 4th vertex on the same plane)
				long d = c + vertexSize;
				if (ny > 0.9999f && same(MemoryUtil.memGetFloat(d + 4), ay)) k = UP;
				else if (ny < -0.9999f && same(MemoryUtil.memGetFloat(d + 4), ay)) k = DOWN;
				else if (nx > 0.9999f && same(MemoryUtil.memGetFloat(d), ax)) k = EAST;
				else if (nx < -0.9999f && same(MemoryUtil.memGetFloat(d), ax)) k = WEST;
				else if (nz > 0.9999f && same(MemoryUtil.memGetFloat(d + 8), az)) k = SOUTH;
				else if (nz < -0.9999f && same(MemoryUtil.memGetFloat(d + 8), az)) k = NORTH;
				switch (k) {
					case UP -> g.plane[UP] = Math.min(g.plane[UP], ay);
					case DOWN -> g.plane[DOWN] = Math.max(g.plane[DOWN], ay);
					case EAST -> g.plane[EAST] = Math.min(g.plane[EAST], ax);
					case WEST -> g.plane[WEST] = Math.max(g.plane[WEST], ax);
					case SOUTH -> g.plane[SOUTH] = Math.min(g.plane[SOUTH], az);
					case NORTH -> g.plane[NORTH] = Math.max(g.plane[NORTH], az);
					default -> {
					}
				}
			}
			group[q] = (byte) k;
			counts[k]++;
		}
		if (counts[UNASSIGNED] == quads) return null;
		for (int i = 0; i < COUNT; i++) g.start[i + 1] = g.start[i] + counts[i];
		// stable partition through a copy
		ByteBuffer copy = MemoryUtil.memAlloc(quads * quadBytes);
		try {
			long src = MemoryUtil.memAddress(copy);
			MemoryUtil.memCopy(base, src, (long) quads * quadBytes);
			int[] next = g.start.clone();
			for (int q = 0; q < quads; q++) {
				int to = next[group[q]]++;
				MemoryUtil.memCopy(src + (long) q * quadBytes, base + (long) to * quadBytes, quadBytes);
			}
		} finally {
			MemoryUtil.memFree(copy);
		}
		return g;
	}

	/** Ints per layer in a packed array: start[0..7], plane[0..6] as float bits, then 1 when the layer has groups. */
	public static final int STRIDE = 16;

	/**
	 * A mesh's groups in one int array, layer ordinal x STRIDE, so drawing a section reads one object instead of a map, its
	 * table, the groups and their two arrays (the draw list is built every frame for every visible section; it was
	 * memory-latency bound). Null when no layer has groups.
	 */
	public static int[] pack(java.util.Map<net.minecraft.client.renderer.chunk.ChunkSectionLayer, FaceGroups> groups) {
		if (groups == null || groups.isEmpty()) return null;
		int[] data = new int[net.minecraft.client.renderer.chunk.ChunkSectionLayer.values().length * STRIDE];
		for (var e : groups.entrySet()) {
			FaceGroups g = e.getValue();
			if (g == null) continue;
			int base = e.getKey().ordinal() * STRIDE;
			System.arraycopy(g.start, 0, data, base, COUNT + 1);
			for (int i = 0; i < COUNT; i++) data[base + COUNT + 1 + i] = Float.floatToRawIntBits(g.plane[i]);
			data[base + STRIDE - 1] = 1;
		}
		return data;
	}

	/** {@link #visibleMask} on packed groups (the same comparisons, the planes read back from their bits). */
	public static int visibleMask(int[] d, int base, double vx, double vy, double vz, boolean ortho) {
		int mask = 1 << UNASSIGNED;
		final double eps = 1e-3;
		if (ortho) {
			if (vy > eps) mask |= 1 << UP;
			if (vy < -eps) mask |= 1 << DOWN;
			if (vx > eps) mask |= 1 << EAST;
			if (vx < -eps) mask |= 1 << WEST;
			if (vz > eps) mask |= 1 << SOUTH;
			if (vz < -eps) mask |= 1 << NORTH;
			if (Math.abs(vy) <= eps) mask |= 1 << UP | 1 << DOWN;
			if (Math.abs(vx) <= eps) mask |= 1 << EAST | 1 << WEST;
			if (Math.abs(vz) <= eps) mask |= 1 << SOUTH | 1 << NORTH;
			return mask;
		}
		int p = base + COUNT + 1;
		if (vy > Float.intBitsToFloat(d[p + UP]) - eps) mask |= 1 << UP;
		if (vy < Float.intBitsToFloat(d[p + DOWN]) + eps) mask |= 1 << DOWN;
		if (vx > Float.intBitsToFloat(d[p + EAST]) - eps) mask |= 1 << EAST;
		if (vx < Float.intBitsToFloat(d[p + WEST]) + eps) mask |= 1 << WEST;
		if (vz > Float.intBitsToFloat(d[p + SOUTH]) - eps) mask |= 1 << SOUTH;
		if (vz < Float.intBitsToFloat(d[p + NORTH]) + eps) mask |= 1 << NORTH;
		return mask;
	}

	private static boolean same(float a, float b) {
		return Math.abs(a - b) < 1e-4f;
	}

	/**
	 * Which groups can face a viewer at {@code (vx, vy, vz)} relative to the section origin (perspective), or, with
	 * {@code ortho}, a viewer looking along -(vx, vy, vz) (the direction toward the light). Bit i = group i.
	 */
	public int visibleMask(double vx, double vy, double vz, boolean ortho) {
		int mask = 1 << UNASSIGNED;
		final double eps = 1e-3;
		if (ortho) {
			if (vy > eps) mask |= 1 << UP;
			if (vy < -eps) mask |= 1 << DOWN;
			if (vx > eps) mask |= 1 << EAST;
			if (vx < -eps) mask |= 1 << WEST;
			if (vz > eps) mask |= 1 << SOUTH;
			if (vz < -eps) mask |= 1 << NORTH;
			// a group exactly edge-on: its quads have no area; keep both to stay conservative
			if (Math.abs(vy) <= eps) mask |= 1 << UP | 1 << DOWN;
			if (Math.abs(vx) <= eps) mask |= 1 << EAST | 1 << WEST;
			if (Math.abs(vz) <= eps) mask |= 1 << SOUTH | 1 << NORTH;
			return mask;
		}
		if (vy > plane[UP] - eps) mask |= 1 << UP;
		if (vy < plane[DOWN] + eps) mask |= 1 << DOWN;
		if (vx > plane[EAST] - eps) mask |= 1 << EAST;
		if (vx < plane[WEST] + eps) mask |= 1 << WEST;
		if (vz > plane[SOUTH] - eps) mask |= 1 << SOUTH;
		if (vz < plane[NORTH] + eps) mask |= 1 << NORTH;
		return mask;
	}
}
