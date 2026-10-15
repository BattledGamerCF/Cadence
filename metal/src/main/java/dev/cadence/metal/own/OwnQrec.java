// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dcadence.own.mesh.qrec=true (with -Dcadence.own.compact): every solid and cutout layer is stored as one 40-byte quad record a quad
 * instead of four 16-byte compact vertices (64 bytes). terrain.metal (OWN_QREC, prepended by OwnTerrain) expands record r's
 * corner c into exactly the compact vertex it replaces wherever a solid / cutout corner is read (ownVertex, ownClip, quadCorner:
 * own_vs, the mesh-shader variants, the per-quad path, quad statistics, prefilter, face stats). Translucent stays 64 bytes a quad.
 * So quad ids (Rec.quadStart + q, the per-quad lists, quadOwner) are record ids for solid / cutout.
 *
 * Record, 10 little-endian words:
 * 0: corner selections: bits 3k..3k+2 corner k's x / y / z takes the second code; bits 12 + 2k, 13 + 2k its u / v the second;
 *    bit 20 colour mode (0: channel c = grey * T.c / 255 in integers as ARGB.multiply, alpha T.a; 1: rgb T, alpha = grey);
 *    bit 31 STUB: word 1 is the index of the quad's 64-byte copy (a quad the record can't hold; other words unused)
 * 1, 2, 3: x, y, z position codes (first | second << 16; OwnQuads' codes, exactPos table codes included)
 * 4, 5: u, v codes (first | second << 16; unorm16 as the compact vertex)
 * 6: tint T, as the compact vertex's colour int (r | g << 8 | b << 16 | a << 24)
 * 7: grey byte of corner k at bits 8k
 * 8, 9: light: corner k's lu, lv bytes at word 8 + (k >> 1), bits 16 * (k & 1)
 */
final class OwnQrec {
	static final int BYTES = 40, META = 1 << 17, STUB = 1 << 31, MODE_ALPHA = 1 << 20;
	/** -Dcadence.own.mesh.qrecCheck: every record decoded back on the CPU and compared with the 64 bytes it replaces (logged). */
	static final boolean CHECK = Boolean.getBoolean("cadence.own.mesh.qrecCheck");
	static final boolean ON = on();
	/**
	 * -Dcadence.own.mesh.qrecVerify: every layer also keeps its 64-byte copy (the record unit's +24 holds the copy's first quad) and
	 * the GPU compares every record corner as quadCorner / ownVertex decode it with the copy (own_qrec_verify, logged).
	 */
	static final boolean VERIFY = ON && Boolean.getBoolean("cadence.own.mesh.qrecVerify") && !OwnMaterials.ON;  // (the unit's +24 is the materials' otherwise)
	private static final AtomicLong QUADS = new AtomicLong(), LAYERS = new AtomicLong(), STUBS = new AtomicLong(), RAW_LAYERS = new AtomicLong(),
		HINTED = new AtomicLong(), UNIFORM = new AtomicLong(), GREY = new AtomicLong(), ALPHA = new AtomicLong(), CHECKED = new AtomicLong(), BAD = new AtomicLong();
	private static final long[] WHY = new long[4];
	private static long logAt;

	private OwnQrec() {
	}

	private static boolean on() {
		if (!Boolean.getBoolean("cadence.own.mesh.qrec")) return false;
		if (!Boolean.getBoolean("cadence.own.compact")) {
			System.out.println("cadence-own mesh qrec: OFF, needs -Dcadence.own.compact");
			return false;
		}
		System.out.println("cadence-own mesh qrec: ON (40-byte quad records for solid and cutout" + (CHECK ? ", CPU round-trip check" : "")
			+ (Boolean.getBoolean("cadence.own.mesh.qrecVerify") ? ", GPU verify with 64-byte copies" : "") + ")");
		return true;
	}

	/**
	 * The record of the compact quad at q (4 x 16 bytes) at r; false if it can't hold it (the caller writes a stub). hinted: the
	 * renderer's pre-tint corner colours were grey (greys, a byte per corner) and its tint (compact colour order) is tint.
	 */
	static boolean write(long r, long q, boolean hinted, int greys, int tint) {
		int sel = 0;
		for (int a = 0; a < 3; a++) {
			int c0 = MemoryUtil.memGetShort(q + 2 * a) & 0xFFFF, c1 = c0;
			for (int k = 1; k < 4; k++) {
				int c = MemoryUtil.memGetShort(q + 16L * k + 2 * a) & 0xFFFF;
				if (c == c0) continue;
				if (c1 == c0) c1 = c;
				else if (c != c1) return why(0);
				sel |= 1 << (3 * k + a);
			}
			MemoryUtil.memPutInt(r + 4 + 4 * a, c0 | c1 << 16);
		}
		for (int a = 0; a < 2; a++) {
			int c0 = MemoryUtil.memGetShort(q + 12 + 2 * a) & 0xFFFF, c1 = c0;
			for (int k = 1; k < 4; k++) {
				int c = MemoryUtil.memGetShort(q + 16L * k + 12 + 2 * a) & 0xFFFF;
				if (c == c0) continue;
				if (c1 == c0) c1 = c;
				else if (c != c1) return why(1);
				sel |= 1 << (12 + 2 * k + a);
			}
			MemoryUtil.memPutInt(r + 16 + 4 * a, c0 | c1 << 16);
		}
		int col0 = MemoryUtil.memGetInt(q + 8), col1 = MemoryUtil.memGetInt(q + 24), col2 = MemoryUtil.memGetInt(q + 40), col3 = MemoryUtil.memGetInt(q + 56);
		int alpha = col0 >>> 24;
		boolean alphaUniform = col1 >>> 24 == alpha && col2 >>> 24 == alpha && col3 >>> 24 == alpha;
		int t, g;
		if (!alphaUniform) {
			// (the AO-in-alpha split of native shading: one rgb, a per-corner alpha)
			int rgb = col0 & 0xFFFFFF;
			if ((col1 & 0xFFFFFF) != rgb || (col2 & 0xFFFFFF) != rgb || (col3 & 0xFFFFFF) != rgb) return why(2);
			t = rgb;
			g = alpha | (col1 >>> 24) << 8 | (col2 >>> 24) << 16 | (col3 >>> 24) << 24;
			sel |= MODE_ALPHA;
			ALPHA.incrementAndGet();
		} else if (hinted && colourOk(t = tint & 0x00FFFFFF | alpha << 24, greys, col0, col1, col2, col3)) {
			g = greys;
			HINTED.incrementAndGet();
		} else if (col0 == col1 && col0 == col2 && col0 == col3) {
			t = col0;
			g = -1;
			UNIFORM.incrementAndGet();
		} else if (isGrey(col0) && isGrey(col1) && isGrey(col2) && isGrey(col3)) {
			t = 0x00FFFFFF | alpha << 24;
			g = col0 & 255 | (col1 & 255) << 8 | (col2 & 255) << 16 | (col3 & 255) << 24;
			GREY.incrementAndGet();
		} else {
			return why(3);
		}
		MemoryUtil.memPutInt(r, sel);
		MemoryUtil.memPutInt(r + 24, t);
		MemoryUtil.memPutInt(r + 28, g);
		for (int k = 0; k < 4; k++) MemoryUtil.memPutShort(r + 32 + 2 * k, MemoryUtil.memGetShort(q + 16L * k + 6));
		QUADS.incrementAndGet();
		return true;
	}

	/** A stub at r: the quad's 64 bytes are at quad index raw (fixed up when the layer is placed). */
	static void stub(long r, int raw) {
		MemoryUtil.memPutInt(r, STUB);
		MemoryUtil.memPutInt(r + 4, raw);
		STUBS.incrementAndGet();
	}

	static boolean isStub(long r) {
		return (MemoryUtil.memGetInt(r) & STUB) != 0;
	}

	private static boolean isGrey(int c) {
		return (c & 255) == (c >> 8 & 255) && (c & 255) == (c >> 16 & 255);
	}

	private static boolean colourOk(int t, int g, int c0, int c1, int c2, int c3) {
		return colour(t, g & 255) == c0 && colour(t, g >>> 8 & 255) == c1 && colour(t, g >>> 16 & 255) == c2 && colour(t, g >>> 24) == c3;
	}

	/** Corner colour from tint t and grey g in mode 0, as terrain.metal decodes it. */
	static int colour(int t, int g) {
		return g * (t & 255) / 255 | g * (t >>> 8 & 255) / 255 << 8 | g * (t >>> 16 & 255) / 255 << 16 | t & 0xFF000000;
	}

	private static boolean why(int k) {
		synchronized (WHY) {
			WHY[k]++;
		}
		return false;
	}

	/** Corner k of record r as the 16-byte compact vertex at d (terrain.metal's decode, for CHECK); arena: base of stub indices. */
	static void decode(long r, int k, long d, long arena) {
		int sel = MemoryUtil.memGetInt(r);
		if ((sel & STUB) != 0) {
			MemoryUtil.memCopy(arena + (long) MemoryUtil.memGetInt(r + 4) * OwnQuads.QUAD + 16L * k, d, 16);
			return;
		}
		for (int a = 0; a < 3; a++) {
			int w = MemoryUtil.memGetInt(r + 4 + 4 * a);
			MemoryUtil.memPutShort(d + 2 * a, (short) ((sel >>> (3 * k + a) & 1) != 0 ? w >>> 16 : w));
		}
		MemoryUtil.memPutShort(d + 6, MemoryUtil.memGetShort(r + 32 + 2 * k));
		int t = MemoryUtil.memGetInt(r + 24), g = MemoryUtil.memGetInt(r + 28) >>> (8 * k) & 255;
		MemoryUtil.memPutInt(d + 8, (sel & MODE_ALPHA) != 0 ? t & 0xFFFFFF | g << 24 : colour(t, g));
		for (int a = 0; a < 2; a++) {
			int w = MemoryUtil.memGetInt(r + 16 + 4 * a);
			MemoryUtil.memPutShort(d + 12 + 2 * a, (short) ((sel >>> (12 + 2 * k + a) & 1) != 0 ? w >>> 16 : w));
		}
	}

	/** CHECK: n records at r against the n compact quads at q (stubs resolved against arena). */
	static void check(long r, long q, int n, long arena) {
		long tmp = MemoryUtil.nmemAlloc(16);
		long bad = 0;
		try {
			for (int i = 0; i < n; i++) {
				for (int k = 0; k < 4; k++) {
					decode(r + (long) i * BYTES, k, tmp, arena);
					long v = q + (long) i * OwnQuads.QUAD + 16L * k;
					if (MemoryUtil.memGetLong(tmp) != MemoryUtil.memGetLong(v) || MemoryUtil.memGetLong(tmp + 8) != MemoryUtil.memGetLong(v + 8)) {
						bad++;
						break;
					}
				}
			}
		} finally {
			MemoryUtil.nmemFree(tmp);
		}
		CHECKED.addAndGet(n);
		if (bad != 0) BAD.addAndGet(bad);
	}

	/** A layer stored as records (raw: from the vanilla-MeshData path, its 64-byte quads kept as the stubs' copies). */
	static void layer(boolean raw) {
		LAYERS.incrementAndGet();
		if (raw) RAW_LAYERS.incrementAndGet();
		long now = System.nanoTime();
		if (now > logAt) {
			synchronized (WHY) {
				if (now <= logAt) return;
				logAt = now + 5_000_000_000L;
				System.out.printf("cadence-own mesh qrec: layers %d (%d from vanilla meshes), records %d (hinted %d, uniform %d, grey %d, alpha %d), stubs %d (position %d, uv %d, alpha %d, colour %d)%s%n",
					LAYERS.get(), RAW_LAYERS.get(), QUADS.get(), HINTED.get(), UNIFORM.get(), GREY.get(), ALPHA.get(), STUBS.get(), WHY[0], WHY[1], WHY[2], WHY[3],
					CHECK ? "; check: " + CHECKED.get() + " quads, " + BAD.get() + " differ" : "");
			}
		}
	}
}
