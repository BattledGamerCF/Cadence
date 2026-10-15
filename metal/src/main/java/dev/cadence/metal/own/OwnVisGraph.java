// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.Direction;

/**
 * -Dcadence.own.mesh.vis=true: vanilla's VisGraph restated: the same rule (fewer than 256 opaque cells: all visible; all opaque:
 * none; else flood every open edge cell's component and connect all the faces it touches), on a long[64] bit set and a reused
 * int queue, faces as a bit mask. The result is the same union of face pairs whatever the flood order. One per thread.
 */
final class OwnVisGraph {
	static final boolean ON = Boolean.getBoolean("cadence.own.mesh.vis");
	private static final ThreadLocal<OwnVisGraph> LOCAL = ThreadLocal.withInitial(OwnVisGraph::new);
	private static final Direction[] DIRECTIONS = Direction.values();
	/** Vanilla's INDEX_OF_EDGES order (x, then y, then z loops; index x | y << 8 | z << 4). */
	private static final int[] EDGES = new int[1352];

	static {
		int n = 0;
		for (int x = 0; x < 16; x++)
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) EDGES[n++] = x | y << 8 | z << 4;
	}

	private final long[] bits = new long[64];
	private final int[] queue = new int[4096];
	private int empty;

	static OwnVisGraph get() {
		OwnVisGraph g = LOCAL.get();
		java.util.Arrays.fill(g.bits, 0);
		g.empty = 4096;
		return g;
	}

	/** Section-relative x, y, z (as vanilla's pos & 15). Called once per opaque cell, as vanilla's setOpaque. */
	void setOpaque(int x, int y, int z) {
		int i = x | y << 8 | z << 4;
		this.bits[i >>> 6] |= 1L << i;
		this.empty--;
	}

	private boolean get(int i) {
		return (this.bits[i >>> 6] & 1L << i) != 0;
	}

	private void set(int i) {
		this.bits[i >>> 6] |= 1L << i;
	}

	VisibilitySet resolve() {
		VisibilitySet set = new VisibilitySet();
		if (4096 - this.empty < 256) {
			set.setAll(true);
		} else if (this.empty == 0) {
			set.setAll(false);
		} else {
			for (int start : EDGES) {
				if (!this.get(start)) {
					int faces = this.flood(start);
					for (int a = 0; a < 6; a++) {
						if ((faces & 1 << a) == 0) continue;
						for (int b = 0; b < 6; b++) if ((faces & 1 << b) != 0) set.set(DIRECTIONS[a], DIRECTIONS[b], true);
					}
				}
			}
		}
		return set;
	}

	/** The faces (bit = Direction.ordinal(): DOWN 0, UP 1, NORTH 2, SOUTH 3, WEST 4, EAST 5) start's open component touches. */
	private int flood(int start) {
		int[] q = this.queue;
		int head = 0, tail = 0, faces = 0;
		q[tail++] = start;
		this.set(start);
		while (head < tail) {
			int i = q[head++];
			int x = i & 15, y = i >> 8 & 15, z = i >> 4 & 15;
			if (x == 0) faces |= 1 << 4;
			else if (x == 15) faces |= 1 << 5;
			if (y == 0) faces |= 1;
			else if (y == 15) faces |= 1 << 1;
			if (z == 0) faces |= 1 << 2;
			else if (z == 15) faces |= 1 << 3;
			if (y != 0 && !this.get(i - 256)) { this.set(i - 256); q[tail++] = i - 256; }
			if (y != 15 && !this.get(i + 256)) { this.set(i + 256); q[tail++] = i + 256; }
			if (z != 0 && !this.get(i - 16)) { this.set(i - 16); q[tail++] = i - 16; }
			if (z != 15 && !this.get(i + 16)) { this.set(i + 16); q[tail++] = i + 16; }
			if (x != 0 && !this.get(i - 1)) { this.set(i - 1); q[tail++] = i - 1; }
			if (x != 15 && !this.get(i + 1)) { this.set(i + 1); q[tail++] = i + 1; }
		}
		return faces;
	}
}
