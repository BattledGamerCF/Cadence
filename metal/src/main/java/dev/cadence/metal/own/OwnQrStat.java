// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

/**
 * -Dcadence.own.mesh.qrstat=true (measurement only): how a quad-record format (one record per quad, the vertex stage expanding it by
 * vertex_id / 4) would store each emitted solid / cutout quad, and the exact bytes it needs:
 * - position: "box corners" when every axis has at most 2 distinct values over the 4 corners (each corner picks lo / hi per axis:
 *   3 axes x 2 position codes = 12 B; exactPos codes keep it exact), else 4 corners x 6 B = 24 B; axis-aligned rectangles (one
 *   axis constant) counted apart;
 * - uv: a rectangle (at most 2 distinct u and 2 distinct v codes: 8 B) else 4 x 4 B = 16 B;
 * - colour: uniform 4 B; "grey per corner x one tint" (the renderer saw grey corners before its tint multiply; the shader would
 *   redo ARGB.multiply in integers) 4 + 4 = 8 B; else 16 B;
 * - light: uniform 2 B, else 4 x 2 B = 8 B;
 * - header 4 B (format, corner selections 12 + 8 bits); total rounded up to 4 B.
 * Logged every 2 s, cumulative per layer: quads, shares, mean bytes, and the byte histogram.
 */
final class OwnQrStat {
	static final boolean ON = Boolean.getBoolean("cadence.own.mesh.qrstat");
	/** Set by OwnBlockRenderer right before a quad is output: [0] = 1 if every corner was grey (r = g = b, a = 255) before the tint. */
	static final ThreadLocal<int[]> GREY = ThreadLocal.withInitial(() -> new int[1]);
	/** per layer: quads, box, axis rect, uv rect, colour uniform, colour grey-tint, light uniform, both uniform, bytes sum, then histogram by 4 B up to 64 */
	private static final long[][] T = new long[2][9 + 17];
	private static final long START = System.nanoTime();
	private static long logAt;

	private OwnQrStat() {
	}

	static void quad(int layer, float[] p, int[] abgr, float[] uv, int[] light) {
		boolean box = true, axisRect = false;
		int constAxes = 0;
		for (int a = 0; a < 3; a++) {
			float v0 = p[a], v1 = v0;
			int distinct = 1;
			for (int v = 1; v < 4; v++) {
				float x = p[v * 3 + a];
				if (x == v0 || x == v1) continue;
				if (distinct == 1) {
					v1 = x;
					distinct = 2;
				} else {
					distinct = 3;
				}
			}
			if (distinct > 2) box = false;
			if (distinct == 1) constAxes++;
		}
		axisRect = box && constAxes == 1;
		boolean uvRect = distinctUv(uv, 0) <= 2 && distinctUv(uv, 1) <= 2;
		boolean colUni = abgr[0] == abgr[1] && abgr[0] == abgr[2] && abgr[0] == abgr[3];
		int[] g = GREY.get();
		boolean greyTint = !colUni && g[0] == 1;
		g[0] = 0;
		boolean lightUni = light[0] == light[1] && light[0] == light[2] && light[0] == light[3];
		int bytes = 4 + (box ? 12 : 24) + (uvRect ? 8 : 16) + (colUni ? 4 : greyTint ? 8 : 16) + (lightUni ? 2 : 8);
		bytes = (bytes + 3) & ~3;
		synchronized (T) {
			long[] t = T[layer];
			t[0]++;
			if (box) t[1]++;
			if (axisRect) t[2]++;
			if (uvRect) t[3]++;
			if (colUni) t[4]++;
			if (greyTint) t[5]++;
			if (lightUni) t[6]++;
			if (colUni && lightUni) t[7]++;
			t[8] += bytes;
			t[9 + Math.min(16, bytes / 4)]++;
			long now = System.nanoTime();
			if (now > logAt) {
				logAt = now + 2_000_000_000L;
				StringBuilder sb = new StringBuilder(String.format("cadence-own mesh qrstat: t %.1f s", (now - START) / 1e9));
				for (int l = 0; l < 2; l++) {
					long[] q = T[l];
					double n = Math.max(1, q[0]);
					sb.append(String.format("; %s %d quads: box %.1f%% (axis rect %.1f%%), uv rect %.1f%%, colour uniform %.1f%% grey-tint %.1f%%, light uniform %.1f%%, both uniform %.1f%%, mean %.2f B; hist",
						l == 0 ? "solid" : "cutout", q[0], q[1] * 100 / n, q[2] * 100 / n, q[3] * 100 / n, q[4] * 100 / n, q[5] * 100 / n, q[6] * 100 / n, q[7] * 100 / n, q[8] / n));
					for (int k = 0; k <= 16; k++) if (q[9 + k] > 0) sb.append(' ').append(k * 4).append(':').append(q[9 + k]);
				}
				System.out.println(sb);
			}
		}
	}

	private static int distinctUv(float[] uv, int c) {
		float a = uv[c], b = a;
		int n = 1;
		for (int v = 1; v < 4; v++) {
			float x = uv[v * 2 + c];
			if (x == a || x == b) continue;
			if (n == 1) {
				b = x;
				n = 2;
			} else {
				return 3;
			}
		}
		return n;
	}
}
