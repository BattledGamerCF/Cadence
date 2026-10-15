// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dcadence.own.mesh.exactPos=true (with -Dcadence.own.compact): exact vertex positions in the 16-byte compact vertex. A coordinate on
 * the 1/2048-block grid in [-8, 22) keeps its grid code (c / 2048 - 8); any other coordinate (vanilla's snow-layer inner face at
 * 0.002 / 16, random block offsets of plants, fluid heights, ...) gets a code >= 61440 indexing a table of its exact float, kept in
 * the arena's first 256 units (4096 floats; the arena is bound wherever positions are decoded, and growth copies it along). The
 * table only grows (one entry per distinct coordinate, section-relative); a full table falls back to the nearest grid code
 * (counted and logged: then that coordinate is as before, rounded). Every producer of compact positions encodes through here.
 */
final class OwnPosTable {
	static final boolean ON = Boolean.getBoolean("cadence.own.mesh.exactPos") && Boolean.getBoolean("cadence.own.compact");
	static final int BASE = 61440, SIZE = 4096, UNITS = SIZE * 4 / 64;
	private static final ConcurrentHashMap<Integer, Integer> CODES = new ConcurrentHashMap<>();
	private static final float[] VALUES = new float[SIZE];
	private static volatile int count;
	private static final AtomicLong OVERFLOW = new AtomicLong();
	private static long logAt, syncLogAt;

	private OwnPosTable() {
	}

	/** The compact code of section-relative coordinate p. */
	static int encode(float p) {
		int g = Math.max(0, Math.min(65535, Math.round((p + 8f) * 2048f)));
		if (g < BASE && g / 2048f - 8f == p) return g;
		Integer c = CODES.get(Float.floatToRawIntBits(p));
		if (c != null) return c;
		synchronized (VALUES) {
			c = CODES.get(Float.floatToRawIntBits(p));
			if (c != null) return c;
			int i = count;
			if (i >= SIZE) {
				long n = OVERFLOW.incrementAndGet();
				long now = System.nanoTime();
				if (now > logAt) {
					logAt = now + 5_000_000_000L;
					System.out.println("cadence-own mesh exactPos: table full (" + SIZE + "), " + n + " coordinates rounded to the grid");
				}
				return Math.min(g, BASE - 1);
			}
			VALUES[i] = p;
			count = i + 1;  // (volatile: the value is visible before the count)
			CODES.put(Float.floatToRawIntBits(p), BASE + i);
			return BASE + i;
		}
	}

	/** The coordinate of code c (CPU side, as the shader decodes it). */
	static float decode(int c) {
		return c >= BASE && ON ? VALUES[c - BASE] : c / 2048f - 8f;
	}

	/**
	 * Copies entries [from, count) into the arena at address a (its first UNITS units) and returns the new count; the render
	 * thread calls it after applying the frame's publish events (each event was queued after the entries its quads use).
	 */
	static int sync(long a, int from) {
		int n = count;
		if (n != from) {
			long now = System.nanoTime();
			if (now > syncLogAt) {
				syncLogAt = now + 5_000_000_000L;
				System.out.println("cadence-own mesh exactPos: table " + n + " of " + SIZE + " entries, " + OVERFLOW.get() + " rounded");
			}
		}
		for (int i = from; i < n; i++) MemoryUtil.memPutFloat(a + 4L * i, VALUES[i]);
		return n;
	}

	static int size() {
		return count;
	}
}
