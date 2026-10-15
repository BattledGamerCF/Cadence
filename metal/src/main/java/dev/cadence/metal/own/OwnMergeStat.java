// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * -Dcadence.own.mesh.mergestat=true (measurement only): how many emitted solid / cutout quads could merge EXACTLY with coplanar
 * neighbours. Eligible: an axis facing, a full 1x1 block face on integer in-plane coordinates, all four corners with the same colour
 * (AO, shade, tint) and the same packed light, a non-animated sprite. Eligible quads of one section, layer, facing and plane with the
 * same uv mapping per corner, colour and light are merged greedily into rectangles on the section's 16 x 16 grid (two quads with one
 * key on one cell: the second stays). Logged cumulatively every 2 s: per layer emitted, eligible, after merge, and why ineligible.
 */
final class OwnMergeStat {
	static final boolean ON = Boolean.getBoolean("cadence.own.mesh.mergestat");
	private static final long START = System.nanoTime();
	/** per layer: [0] emitted, [1] eligible (placed on the grid), [2] rectangles, [3..6] ineligible by reason 1..4, [7] same-key duplicates on a cell */
	private static final long[][] TOTAL = new long[2][8];
	private static long logAt;

	private record Key(float plane, long k0, long k1, long k2) {
	}

	private OwnMergeStat() {
	}

	static void count(OwnQuads quads) {
		long[][] add = new long[2][8];
		for (int l = 0; l < 2; l++) {
			OwnQuads.Layer layer = quads.layers[l];
			long[] a = add[l];
			for (int b = 0; b < OwnQuads.BUCKETS; b++) {
				int n = layer.count[b];
				a[0] += n;
				Map<Key, BitSet> groups = new HashMap<>();
				for (int i = 0; i < n; i++) {
					long m2 = layer.mk2[b][i];
					int reason = (int) (m2 >>> 48);
					if (reason != 0) {
						a[2 + reason]++;
						continue;
					}
					int ca = (int) (m2 >>> 32 & 255), cb = (int) (m2 >>> 40 & 255);
					BitSet cells = groups.computeIfAbsent(new Key(layer.plane[b][i], layer.mk0[b][i], layer.mk1[b][i], m2 & 0xFFFFFFFFL), k -> new BitSet(256));
					int c = cb * 16 + ca;
					if (cells.get(c)) {
						a[7]++;  // a second quad with the same key on one cell: stays unmerged
						continue;
					}
					cells.set(c);
					a[1]++;
				}
				for (BitSet cells : groups.values()) a[2] += rects(cells);
			}
		}
		synchronized (TOTAL) {
			for (int l = 0; l < 2; l++) for (int k = 0; k < 8; k++) TOTAL[l][k] += add[l][k];
			long now = System.nanoTime();
			if (now > logAt) {
				logAt = now + 2_000_000_000L;
				StringBuilder sb = new StringBuilder(String.format("cadence-own mesh mergestat: t %.1f s", (now - START) / 1e9));
				for (int l = 0; l < 2; l++) {
					long[] t = TOTAL[l];
					// after merge = emitted - eligible + rectangles (ineligible and duplicates stay as they are)
					long after = t[0] - t[1] + t[2];
					sb.append(String.format("; %s emitted %d, eligible %d, rects %d, after merge %d (%.1f%%), ineligible: any-facing %d, not unit face %d, corners differ %d, sprite %d, dup %d",
						l == 0 ? "solid" : "cutout", t[0], t[1], t[2], after, 100.0 * after / Math.max(1, t[0]), t[3], t[4], t[5], t[6], t[7]));
				}
				System.out.println(sb);
			}
		}
	}

	/** Greedy rectangles over a 16 x 16 cell set (row-major: widest run, then as many rows as the run fits). */
	private static int rects(BitSet cells) {
		BitSet left = (BitSet) cells.clone();
		int count = 0;
		for (int c = left.nextSetBit(0); c >= 0; c = left.nextSetBit(c)) {
			int a = c & 15, b = c >> 4, w = 1;
			while (a + w < 16 && left.get(b * 16 + a + w)) w++;
			int h = 1;
			outer:
			while (b + h < 16) {
				for (int x = a; x < a + w; x++) if (!left.get((b + h) * 16 + x)) break outer;
				h++;
			}
			for (int y = b; y < b + h; y++) left.clear(y * 16 + a, y * 16 + a + w);
			count++;
		}
		return count;
	}
}
