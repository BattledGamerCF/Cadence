// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.chunk;

/**
 * C1: Sodium's DirectionalVisGraph.resolveWithDirections, computed with 16-bit row masks instead of a cell-by-cell DFS.
 *
 * <p>What Sodium computes: for a direction set D (one direction per axis, e.g. down + north + west) and each of the three faces F
 * opposite to D, a flood from every open cell on F that may only step in D's three directions through open (non-opaque) cells;
 * the face F "sees" each D-face that some flooded cell touches, and itself if it has any open cell. Its DFS shares one visited set
 * across the seeds of a face and never stops early (the early-out compares against a mask whose bit 10 is always set, since
 * GraphDirectionSet.of(~set) is 1 << (~set & 31)), so the result is exactly that union: the faces reachable by monotone paths.
 *
 * <p>Monotone paths make the reachable set a single sweep: layers in D's y order, rows in D's z order, and inside a row an
 * occluded fill along D's x direction (Kogge-Stone, 4 steps). A cell is reached iff it is open and its predecessor in some D
 * direction is reached or it is a seed, which is what the sweep evaluates in dependency order.
 *
 * <p>Bit layout matches Sodium's BitArray of opaque blocks: index x | z << 4 | y << 8, 64 bits per long, so long (y << 2 | z >> 2)
 * holds rows z & ~3 .. z | 3 of layer y, 16 bits each, x in the low bits.
 */
public final class VisSweep {
	private VisSweep() { }

	/** Faces reached (bits 0-5, GraphDirection order) | 64 if face {@code origin} has an open cell; 0 if it has none. */
	public static int reach(long[] opaque, int directionSet, int origin) {
		boolean down = (directionSet & 1) != 0, north = (directionSet & 4) != 0, west = (directionSet & 16) != 0;
		int yFace = down ? 0 : 1, zFace = north ? 2 : 3, xFace = west ? 4 : 5;
		int seedKind = origin == (down ? 1 : 0) ? 0 : origin == (north ? 3 : 2) ? 1 : 2;
		int yStart = down ? 15 : 0, yStep = down ? -1 : 1;
		int zStart = north ? 15 : 0, zStep = north ? -1 : 1;
		int xSeed = west ? 0x8000 : 0x0001, xEnd = west ? 0x0001 : 0x8000;
		int zEnd = north ? 0 : 15;
		int allFaces = 1 << yFace | 1 << zFace | 1 << xFace;
		int[] prev = new int[16], cur = new int[16];
		boolean seeded = false;
		int faces = 0;
		for (int yi = 0, y = yStart; yi < 16; yi++, y += yStep) {
			int rowPrev = 0, layer = 0;
			for (int zi = 0, z = zStart; zi < 16; zi++, z += zStep) {
				int open = ~(int) (opaque[y << 2 | z >>> 2] >>> ((z & 3) << 4)) & 0xFFFF;
				int in = (prev[z] | rowPrev) & open;
				if (seedKind == 0 ? yi == 0 : seedKind == 1 ? zi == 0 : true) {
					int seeds = seedKind == 2 ? open & xSeed : open;
					seeded |= seeds != 0;
					in |= seeds;
				}
				int r = west ? fillDown(in, open) : fillUp(in, open);
				cur[z] = r;
				rowPrev = r;
				layer |= r;
				if (r != 0) {
					if (z == zEnd) faces |= 1 << zFace;
					if ((r & xEnd) != 0) faces |= 1 << xFace;
				}
			}
			if (yi == 15 && layer != 0) faces |= 1 << yFace;
			int[] t = prev;
			prev = cur;
			cur = t;
			// Faces only accumulate: once all three are reached nothing can change. A y-face seeds only the first layer, so an
			// empty layer there means nothing further is reachable.
			if (faces == allFaces || layer == 0 && seedKind == 0) break;
		}
		return seeded ? faces | 64 : 0;
	}

	/** Propagate set bits toward bit 0 through set bits of {@code open} (g must be a subset of open). */
	static int fillDown(int g, int open) {
		int p = open;
		g |= p & g >>> 1;
		p &= p >>> 1;
		g |= p & g >>> 2;
		p &= p >>> 2;
		g |= p & g >>> 4;
		p &= p >>> 4;
		g |= p & g >>> 8;
		return g;
	}

	/** Propagate set bits toward bit 15 through set bits of {@code open} (g must be a subset of open). */
	static int fillUp(int g, int open) {
		int p = open;
		g |= p & g << 1;
		p &= p << 1;
		g |= p & g << 2;
		p &= p << 2;
		g |= p & g << 4;
		p &= p << 4;
		g |= p & g << 8;
		return g & 0xFFFF;
	}
}
