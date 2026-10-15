// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dcadence.own.mesh.tieGroups=true|verify (with -Dcadence.own.mesh; default off): per section mesh, its identical-corner groups of opaque
 * quads (solid and cutout), for per-quad phase selection (PLAN-perquad-product.md §3.1, step P4). Nothing drawn changes.
 * <p>
 * <b>Key.</b> A quad's 4 corners as their compact position codes (x | y << 16 | z << 32; OwnPosTable keeps one code per distinct
 * float, so equal codes are equal decoded positions, exactPos included), sorted. A corner group is split by winding only when every
 * member is <b>certified simple</b> (certify: both triangles (0,1,2) / (2,3,0) nondegenerate, the 4 corners exactly coplanar, the two
 * triangles facing the same way, all exact in the decoded positions). Two certified quads with the same corners and opposite winding
 * lie in one plane with every triangle of one facing against every triangle of the other, so back-face culling never draws both at
 * a pixel: they never tie. Anything else (a self-intersecting or non-planar quad: REVIEW-tiegroups' bow-tie counterexample, sloped
 * fluid tops) groups by corners alone. On the captures this keeps the back-to-back leaves faces (77-85% of corner-only groups) out
 * while every non-certified pair stays grouped: S members 56-1,162 a snapshot (corners alone: 56k-274k).
 * <p>
 * <b>Groups</b> (build, mesh-relative): [solid units, cutout units, group count, then per group a header n | S | X and its n members
 * u << 6 | lq ascending], u the mesh's opaque unit (solid units first, as OwnTerrain.buildRecords lays out the records), lq the quad in
 * the unit. S: two or more members in one layer (the per-quad tie list's members); X: members in both layers. OwnTerrain.publish
 * writes them with absolute records (r << 6 | lq, R_n order) into the shared tie-group buffer, the section's block at Section word 7
 * (pad1; 0 = none), and sets the per-ID S bits. Block: [words after these two, group count], then the groups.
 * <p>
 * <b>Fail-closed.</b> pad1 = UNKNOWN (0xFFFFFFFF) when a section's groups aren't known (its mesh wasn't grouped: vanilla's store
 * path, or too large; or the worker's unit counts don't match the stored layers): a consumer must not split that section's opaque
 * quads between phases. Off entirely (ON false, logged) with -Dcadence.metal.inFlight > 2: later()'s 3-frame delay, which keeps a
 * group block or an S-bit range from being reused while a frame in flight reads it, only covers 2 frames in flight.
 * <p>
 * <b>Cross-section</b> (Dir): a quad with a corner on or outside its section's bounds may coincide with a quad of another section,
 * which no mesh sees. Each such quad (build's edges) goes into a directory keyed by its exact world corners when its section is
 * installed (OwnTerrain.setSlot, render thread) and leaves it when the section changes; the directory counts the live cross-section
 * tie candidates (identical world corners, unless both certified with opposite winding). A consumer must use its ordered fallback
 * for any frame while that count, or the count of UNKNOWN sections, is above zero (OwnTerrain.tieFallback). The captures had none.
 */
public final class OwnTieGroups {
	private static final String MODE = System.getProperty("cadence.own.mesh.tieGroups", "false");
	/** The group blocks and S bits (P4's per-quad metadata) were asked for. */
	static final boolean BLOCKS = MODE.equals("true") || MODE.equals("verify");
	/**
	 * -Dcadence.own.frag.tieClose (OwnFrag.TIE_CLOSE): the tie components as record rings for uocc's promotion closure (OwnTerrain.tieRing,
	 * terrain.metal fragTiePromote). Groups then have no winding split (a superset of the ties: an extra unit drawn in phase A costs a
	 * draw, never order), and the cross-section directory excludes a back-to-back pair only when its corners are identical
	 * (REVIEW-tiegroups3 §B).
	 */
	static final boolean CLOSE = Boolean.getBoolean("cadence.own.frag.tieClose");
	/**
	 * (experiment, -Dcadence.own.frag.tieCloseX) tieClose's components only from groups with members in both layers (X: solid and
	 * cutout, the grass side class); same-layer groups (S) don't join units. Not a full tie closure: same-layer ties across phases stay.
	 */
	static final boolean CLOSE_X = Boolean.getBoolean("cadence.own.frag.tieCloseX");
	/**
	 * tieClose alone (no P4 metadata asked for): groups only solid / cutout pairs (buildX), the class that ties across phases in
	 * vanilla's SOLID-then-CUTOUT order (grass_block_side's overlay over its base; cutout over solid). Same-layer pairs aren't
	 * grouped and the directory counts only pairs of different layers. Much cheaper: a mesh without cutout quads does no matching.
	 */
	static final boolean X_ONLY = CLOSE && !BLOCKS;
	private static final boolean REQUESTED = BLOCKS || CLOSE;
	static final int IN_FLIGHT = Integer.getInteger("cadence.metal.inFlight", 2);
	static final boolean ON = REQUESTED && IN_FLIGHT <= 2;
	/** Groups split by winding (certified simple, identical corners): P4's diagnostic metadata only, never with tieClose. */
	static final boolean WINDING = !CLOSE;
	/** verify: at publish, every group's members decoded back from the arena (the GPU's own data) must have its key. */
	static final boolean VERIFY = ON && BLOCKS && MODE.equals("verify");
	static final int S = 1 << 16, X = 1 << 17;
	/** Section.pad1 for a section whose groups aren't known (see the class comment). */
	static final int UNKNOWN = 0xFFFFFFFF;
	/** Ints a cross-section edge record takes in build's edges: u << 6 | lq, layer | (certify + 1) << 1, then the 12 codes as 6 ints. */
	static final int EDGE_INTS = 8;
	/** An edge record's certify bits (1-2) before Dir.cert computes them. */
	static final int CERT_LAZY = 3;
	private static final boolean REACH_DEBUG = Boolean.getBoolean("cadence.own.tieReachDebug");
	/**
	 * A reaching quad (Dir) from its 12 codes at raw[o] and its mesher bucket: 8 if it overhangs (beyond the section by more than
	 * TOL), 0 if it is within 2 TOL of a boundary plane and not an axis quad exactly in that plane facing out of the section (near no
	 * other boundary plane), else -1. Grid codes (1/2048 apart, 2 TOL = one step) by integer compares; a table code decoded.
	 */
	static int reaching(int[] raw, int o, int bucket) {
		boolean over = false;
		int plane = 0;  // (bit k * 2 + side: every corner within 2 TOL of coordinate k = 0 (1) / 16 (2))
		for (int k = 0; k < 3; k++) {
			boolean lo = true, hi = true;
			for (int v = 0; v < 4; v++) {
				int c = raw[o + 3 * v + k];
				if (c >= OwnPosTable.BASE) {
					float f = OwnPosTable.decode(c);
					over |= f < -TOL || f > 16 + TOL;
					lo &= Math.abs(f) <= 2 * TOL;
					hi &= Math.abs(f - 16) <= 2 * TOL;
				} else {
					over |= c < G0 || c > G16;
					lo &= c >= G0 - 1 && c <= G0 + 1;
					hi &= c >= G16 - 1 && c <= G16 + 1;
				}
			}
			plane |= (lo ? 1 : 0) << 2 * k | (hi ? 2 : 0) << 2 * k;
		}
		if (over) return 8;
		if (plane == 0) return -1;
		if (bucket < 6) {
			int a = bucket >> 1, c0 = raw[o + a], side = (bucket & 1) == 0 ? 2 : 1;
			if ((plane & ~(3 << 2 * a)) == 0 && (plane >> 2 * a & 3) == side && c0 == (side == 2 ? G16 : G0)) return -1;
		}
		return 0;
	}

	/** The mesher's facing bucket (OwnQuads.Layer.quad's rule) of 12 codes at raw[o], from their decoded corners: tests. */
	static int bucketOf(int[] raw, int o) {
		float[] p = new float[12];
		decode(raw, o, p);
		float x0 = p[0], y0 = p[1], z0 = p[2], x1 = p[3], y1 = p[4], z1 = p[5], x2 = p[6], y2 = p[7], z2 = p[8], x3 = p[9], y3 = p[10], z3 = p[11];
		float ux = x1 - x0, uy = y1 - y0, uz = z1 - z0, vx = x2 - x0, vy = y2 - y0, vz = z2 - z0;
		float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		float wx = x3 - x2, wy = y3 - y2, wz = z3 - z2, tx = x0 - x2, ty = y0 - y2, tz = z0 - z2;
		float mx = wy * tz - wz * ty, my = wz * tx - wx * tz, mz = wx * ty - wy * tx;
		if (x0 == x1 && x1 == x2 && x2 == x3 && ny == 0 && nz == 0 && my == 0 && mz == 0 && nx != 0 && Math.signum(nx) == Math.signum(mx)) return nx > 0 ? 0 : 1;
		if (y0 == y1 && y1 == y2 && y2 == y3 && nx == 0 && nz == 0 && mx == 0 && mz == 0 && ny != 0 && Math.signum(ny) == Math.signum(my)) return ny > 0 ? 2 : 3;
		if (z0 == z1 && z1 == z2 && z2 == z3 && nx == 0 && ny == 0 && mx == 0 && my == 0 && nz != 0 && Math.signum(nz) == Math.signum(mz)) return nz > 0 ? 4 : 5;
		return 6;
	}

	/** Grid position codes of local 0 and 16 (OwnPosTable: (v + 8) * 2048). */
	static final int G0 = 8 * 2048, G16 = 24 * 2048;
	static final LongAdder MESHES = new LongAdder(), GROUPS = new LongAdder(), X_GROUPS = new LongAdder(), S_GROUPS = new LongAdder(), MEMBERS = new LongAdder(),
		S_MEMBERS = new LongAdder(), BUILD_NS = new LongAdder(), QUADS = new LongAdder(), EDGES = new LongAdder(), EXACT = new LongAdder(), NEAR = new LongAdder(),
		KEY_NS = new LongAdder(), EDGE_NS = new LongAdder(), GROUP_NS = new LongAdder(), RUNS_NS = new LongAdder(), EXACTX_NS = new LongAdder(), NEARX_NS = new LongAdder(),
		/** reaching quads built: overhangs; axis quads near a boundary plane (inward / not exactly in it); bucket 6 near one; by layer */
		REACH_OVER = new LongAdder(), REACH_AXIS = new LongAdder(), REACH_ANY = new LongAdder(), REACH_CUT = new LongAdder();
	private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

	static {
		// (the own renderer's 3-slot section tables and 3-frame deferred frees assume <= 2 frames in flight whatever tieClose does:
		// REVIEW-temporal, pre-existing; tieClose's rings and blocks rely on the same delay, so it reports itself off there)
		if (CLOSE && !ON) System.out.println("cadence-own frag tieClose: OFF: -Dcadence.metal.inFlight=" + IN_FLIGHT + " > 2 (frames draw as without the flag; the renderer's"
			+ " 3-slot tables assume <= 2 frames in flight, a pre-existing limit, REVIEW-temporal)");
		if (REQUESTED && !ON) System.out.println("cadence-own mesh tieGroups: OFF (fail-closed): -Dcadence.metal.inFlight=" + IN_FLIGHT
			+ " > 2, and later()'s 3-frame delay only keeps blocks and S-bit ranges safe with <= 2 frames in flight");
	}

	private OwnTieGroups() {
	}

	/** A build's result: the groups (see the class comment) and the cross-section edge records. */
	public record Result(int[] groups, int[] edges) {
	}

	private static final class Scratch {
		long[] k = new long[0], sort = new long[0];
		int[] raw = new int[0], unit = new int[0], lq = new int[0];
		byte[] layer = new byte[0], bucket = new byte[0];
		/** X_ONLY: quad q has its sorted key (groupsX probes only those solid quads); groupsX's reusable arrays */
		boolean[] keyed = new boolean[0], root = new boolean[0], inner = new boolean[0];
		final int[][] count = new int[2][OwnQuads.BUCKETS];
		long[] byRoot = new long[0];
		int[] tab = new int[0];
		final long[] c = new long[4];
		int[] parent = new int[0];

		int[] par(int n) {
			if (this.parent.length < n) this.parent = new int[Math.max(n, this.parent.length * 2)];
			return this.parent;
		}

		void ensure(int n) {
			if (this.unit.length >= n) return;
			int c = Math.max(n, this.unit.length * 2);
			this.k = new long[4 * c];
			this.raw = new int[12 * c];
			this.sort = new long[c];
			this.unit = new int[c];
			this.lq = new int[c];
			this.layer = new byte[c];
			this.bucket = new byte[c];
			this.keyed = new boolean[c];
			this.inner = new boolean[c];
			this.root = new boolean[c];
			this.byRoot = new long[c];
		}
	}

	/** Worker thread, after meshing: quads' solid and cutout groups (draw units of run quads) and edge records. Never null, never throws. */
	static Result build(OwnQuads quads, int run) {
		long t0 = System.nanoTime();
		Scratch s = SCRATCH.get();
		int n = 0;
		for (int l = 0; l < 2; l++) n += quads.layers[l].total();
		int[] units = new int[2];
		// (runs: the store's own work, memoized here on the worker, which the store then reuses; timed apart: RUNS_NS)
		long tr0 = System.nanoTime();
		for (int l = 0; l < 2; l++) if (quads.layers[l].total() > 0) units[l] = quads.layers[l].runs(run).length / OwnTerrain.RUN_INTS;
		RUNS_NS.add(System.nanoTime() - tr0);
		if (n >= 1 << 21) return new Result(new int[] {-1, -1, 0}, new int[0]);  // (too large: published UNKNOWN)
		s.ensure(Math.max(n, 1));
		float[] ext = {0, 0, 0, 16, 16, 16};  // (the mesh's local extent, at least the section; extend() keeps the largest seen)
		// (X_ONLY: a mesh without both layers pairs nothing: its quads need only their codes, for the edge records; with both, a solid
		// quad needs its sorted key only if a cutout quad could have its exact corners: one of its bucket, or a bucket 6 cutout quad
		// exactly planar on its axis; a bucket 6 solid quad if there is a bucket 6 cutout quad or it is exactly planar on an axis with
		// cutout quads; identical corners lie in one plane, and an exactly planar axis quad with a consistent winding is in buckets 0-5)
		boolean keys = !X_ONLY || quads.layers[0].total() > 0 && quads.layers[1].total() > 0;
		int[] cb = new int[OwnQuads.BUCKETS];
		boolean[] planar6 = new boolean[3];
		if (X_ONLY && keys) {
			OwnQuads.Layer cut = quads.layers[1];
			for (int b = 0; b < OwnQuads.BUCKETS; b++) cb[b] = cut.count[b];
			for (int i = 0, cnt = cut.count[6]; i < cnt; i++) {
				long q = cut.addr[6] + (long) i * OwnQuads.QUAD;
				for (int a = 0; a < 3; a++) planar6[a] |= exactlyPlanar(q, a);
			}
		}
		int at = 0, unitBase = 0;
		for (int l = 0; l < 2; l++) Arrays.fill(s.count[l], 0);
		for (int l = 0; l < 2; l++) {
			OwnQuads.Layer layer = quads.layers[l];
			if (layer.total() == 0) continue;
			long tr = System.nanoTime();
			int[] runs = layer.runs(run);  // (orders the layer: arena order from here on; memoized, the store reuses it)
			RUNS_NS.add(System.nanoTime() - tr);
			int nu = runs.length / OwnTerrain.RUN_INTS;
			for (int u = 0; u < nu; u++) {
				// (the scope guard's local extent: the unit's whole-block box, section-relative + 16, which holds its quads)
				int ul = runs[u * OwnTerrain.RUN_INTS + 4], uh = runs[u * OwnTerrain.RUN_INTS + 5];
				for (int d = 0; d < 3; d++) {
					ext[d] = Math.min(ext[d], (ul >>> 8 * d & 255) - 16);
					ext[3 + d] = Math.max(ext[3 + d], (uh >>> 8 * d & 255) - 16);
				}
				int first = runs[u * OwnTerrain.RUN_INTS], c = runs[u * OwnTerrain.RUN_INTS + 1];
				for (int j = 0; j < c; j++) {
					s.unit[at + first + j] = unitBase + u;
					s.lq[at + first + j] = j;
				}
			}
			int k = at;
			for (int b = 0; b < OwnQuads.BUCKETS; b++) s.count[l][b] = layer.count[b];
			for (int b = 0; b < OwnQuads.BUCKETS; b++) {
				long base = layer.addr[b];
				int[] blo = layer.lo[b], bhi = layer.hi[b];
				for (int i = 0, cnt = layer.count[b]; i < cnt; i++, k++) {
					long qa = base + (long) i * OwnQuads.QUAD;
					boolean need = keys && (!X_ONLY || l == 1 || needsKey(qa, b, cb, planar6));
					if (need) key(s, k, qa, l);
					else codes(s, k, qa, l);
					s.keyed[k] = need;
					s.bucket[k] = (byte) b;
					// (the mesher's whole-block box, section-relative + 16: within blocks 1 .. 15 on every axis, the quad is more than 2 TOL
					// from every boundary plane and inside the section, so it can't be a reaching quad)
					int bl = blo[i], bh = bhi[i];
					s.inner[k] = (bl & 255) >= 17 && (bl >>> 8 & 255) >= 17 && (bl >>> 16 & 255) >= 17 && (bh & 255) <= 31 && (bh >>> 8 & 255) <= 31 && (bh >>> 16 & 255) <= 31;
				}
			}
			at = k;
			unitBase += nu;
		}
		// cross-section edge records: the reaching quads (Dir): beyond the section's bounds by more than TOL (bit 3), or every corner
		// within 2 TOL of a boundary plane, unless an axis quad exactly in that plane facing out of the section. On grid codes (1/2048
		// apart, 2 TOL = one step) by integer compares; a table code decoded
		long t1 = System.nanoTime();
		KEY_NS.add(t1 - t0);
		int[] edges = new int[64];
		int ne = 0;
		for (int q = 0; q < n; q++) {
			if (s.inner[q]) continue;
			int r = reaching(s.raw, 12 * q, s.bucket[q]);
			if (r < 0) continue;
			(r == 8 ? REACH_OVER : s.bucket[q] < 6 ? REACH_AXIS : REACH_ANY).increment();
			if (REACH_DEBUG && r == 0 && s.bucket[q] < 6 && REACH_AXIS.sum() <= 12) {
				float[] p = new float[12];
				decode(s.raw, 12 * q, p);
				System.out.println("cadence-own tieGroups reach-debug: layer " + s.layer[q] + " bucket " + s.bucket[q] + " corners " + Arrays.toString(p) + " codes "
					+ Arrays.toString(Arrays.copyOfRange(s.raw, 12 * q, 12 * q + 12)));
			}
			if (s.layer[q] == 1) REACH_CUT.increment();
			if (ne + EDGE_INTS > edges.length) edges = Arrays.copyOf(edges, edges.length * 2);
			edges[ne] = s.unit[q] << 6 | s.lq[q];
			edges[ne + 1] = s.layer[q] | CERT_LAZY << 1 | r | s.bucket[q] << 4;  // (certify on demand: Dir.cert, only for identical corners)
			for (int i = 0; i < 6; i++) edges[ne + 2 + i] = s.raw[12 * q + 2 * i] | s.raw[12 * q + 2 * i + 1] << 16;
			ne += EDGE_INTS;
		}
		EDGES.add(ne / EDGE_INTS);
		extend(ext);
		long t2 = System.nanoTime();
		EDGE_NS.add(t2 - t1);
		if (X_ONLY) {
			int[] gx = groupsX(s, n, units);
			GROUP_NS.add(System.nanoTime() - t2);
			MESHES.increment();
			QUADS.add(n);
			BUILD_NS.add(System.nanoTime() - t0);
			return new Result(gx, Arrays.copyOf(edges, ne));
		}
		// groups: a union-find over the quads. Equal sorted codes join (sorted by a 43-bit hash of them | the quad's index, exact
		// compare inside equal-hash runs); then, as the shader's float32 world positions can make near positions equal
		// (REVIEW-tiegroups2), a quad with an exactPos table code joins every quad of the section within TOL of it, corner for corner
		// (grid codes are 1/2048 apart, more than TOL, so two grid quads are near only if equal)
		int[] par = s.par(n);
		for (int i = 0; i < n; i++) par[i] = i;
		for (int i = 0; i < n; i++) s.sort[i] = hash(s, i) << 21 | i;
		Arrays.sort(s.sort, 0, n);
		for (int i = 0; i < n; ) {
			int j = i + 1;
			while (j < n && s.sort[j] >>> 21 == s.sort[i] >>> 21) j++;
			for (int a2 = i; a2 < j; a2++)
				for (int b2 = a2 + 1; b2 < j; b2++) {
					int qa = (int) (s.sort[a2] & 0x1FFFFF), qb = (int) (s.sort[b2] & 0x1FFFFF);
					if (same(s, qa, qb)) union(par, qa, qb);
				}
			i = j;
		}
		boolean anyTable = false;
		if (OwnPosTable.ON) for (int i = 0; i < 12 * n && !anyTable; i++) anyTable = s.raw[i] >= OwnPosTable.BASE;
		if (anyTable) {
			// (cells of 1/8 block by centroid: near quads share a cell or a neighbour one)
			long[] cells = new long[n];
			for (int q = 0; q < n; q++) cells[q] = cellKey(s.raw, 12 * q) << 21 | q;
			Arrays.sort(cells);
			float[] pa = new float[12], pb = new float[12];
			for (int q = 0; q < n; q++) {
				boolean table = false;
				for (int i = 0; i < 12 && !table; i++) table = s.raw[12 * q + i] >= OwnPosTable.BASE;
				if (!table) continue;
				decode(s.raw, 12 * q, pa);
				long c = cellKey(s.raw, 12 * q);
				for (int dx = -1; dx <= 1; dx++)
					for (int dy = -1; dy <= 1; dy++)
						for (int dz = -1; dz <= 1; dz++) {
							long key = c + ((long) dx << 18) + ((long) dy << 9) + dz;
							int lo = lowerBound(cells, key << 21);
							for (int k = lo; k < n && cells[k] >>> 21 == key; k++) {
								int o = (int) (cells[k] & 0x1FFFFF);
								if (o == q || find(par, o) == find(par, q)) continue;
								decode(s.raw, 12 * o, pb);
								if (!near(pa, pb)) continue;
								union(par, o, q);
								NEAR.increment();
							}
						}
			}
		}
		// the components of two or more: each a group, split by winding only if every member is certified simple
		long[] byRoot = new long[n];
		for (int i = 0; i < n; i++) byRoot[i] = (long) find(par, i) << 21 | i;
		Arrays.sort(byRoot);
		int[] out = new int[3 + 64];
		int w = 3, groups = 0, members = 0, xg = 0, sg = 0, sm = 0;
		int[] grp = new int[8], sign = new int[8];
		for (int i = 0; i < n; ) {
			int j = i + 1;
			while (j < n && byRoot[j] >>> 21 == byRoot[i] >>> 21) j++;
			int m = j - i;
			if (m >= 2) {
				if (grp.length < m) {
					grp = new int[m];
					sign = new int[m];
				}
				for (int g = 0; g < m; g++) grp[g] = (int) (byRoot[i + g] & 0x1FFFFF);
				// (winding split: only for P4's metadata, and only when every member has the first one's exact corner codes: near
				// quads with opposite certify() signs can both face the camera, REVIEW-tiegroups3 §B)
				boolean all = WINDING;
				for (int g = 0; g < m && all; g++) {
					sign[g] = certify(s.raw, 12 * grp[g]);
					all = sign[g] != 0 && same(s, grp[0], grp[g]);
				}
				for (int part = all ? 0 : 2; part < 3; part++) {
					if (part == 2 && all) break;
					int want = part == 0 ? 1 : -1, cnt = 0;
					int[] mem = new int[m];
					int[] nl = new int[2];
					for (int g = 0; g < m; g++) {
						if (part < 2 && sign[g] != want) continue;
						nl[s.layer[grp[g]]]++;
						mem[cnt++] = s.unit[grp[g]] << 6 | s.lq[grp[g]];
					}
					if (cnt < 2) continue;
					Arrays.sort(mem, 0, cnt);  // (R_n: record, then local quad)
					boolean sFlag = nl[0] >= 2 || nl[1] >= 2, xFlag = nl[0] > 0 && nl[1] > 0;
					if (w + 1 + cnt > out.length) out = Arrays.copyOf(out, Math.max(out.length * 2, w + 1 + cnt));
					out[w++] = cnt | (sFlag ? S : 0) | (xFlag ? X : 0);
					System.arraycopy(mem, 0, out, w, cnt);
					w += cnt;
					groups++;
					members += cnt;
					if (xFlag) xg++;
					if (sFlag) {
						sg++;
						sm += (nl[0] >= 2 ? nl[0] : 0) + (nl[1] >= 2 ? nl[1] : 0);
					}
				}
			}
			i = j;
		}
		MESHES.increment();
		QUADS.add(n);
		BUILD_NS.add(System.nanoTime() - t0);
		GROUPS.add(groups);
		MEMBERS.add(members);
		X_GROUPS.add(xg);
		S_GROUPS.add(sg);
		S_MEMBERS.add(sm);
		out[0] = units[0];
		out[1] = units[1];
		out[2] = groups;
		return new Result(Arrays.copyOf(out, groups == 0 ? 3 : w), Arrays.copyOf(edges, ne));
	}

	/** Quad k's codes (vertex order, raw) and sorted corner key from its 64-byte compact quad at q (position codes at +0, +2, +4). */
	/** All 4 corners of the compact quad at q share one code on axis a. */
	private static boolean exactlyPlanar(long q, int a) {
		int c = MemoryUtil.memGetShort(q + 2L * a) & 0xFFFF;
		for (int v = 1; v < 4; v++) if ((MemoryUtil.memGetShort(q + v * 16L + 2L * a) & 0xFFFF) != c) return false;
		return true;
	}

	/** X_ONLY: a solid quad at q in bucket b can have a cutout quad's exact corners (see build). */
	private static boolean needsKey(long q, int b, int[] cb, boolean[] planar6) {
		if (b < 6) return cb[b] > 0 || planar6[b >> 1];
		if (cb[6] > 0) return true;
		for (int a = 0; a < 3; a++) if (cb[2 * a] + cb[2 * a + 1] > 0 && exactlyPlanar(q, a)) return true;
		return false;
	}

	/** Quad k's codes (vertex order) and layer, without the sorted key. */
	private static void codes(Scratch s, int k, long q, int layer) {
		for (int v = 0; v < 4; v++) {
			long a = q + v * 16L;
			s.raw[12 * k + 3 * v] = MemoryUtil.memGetShort(a) & 0xFFFF;
			s.raw[12 * k + 3 * v + 1] = MemoryUtil.memGetShort(a + 2) & 0xFFFF;
			s.raw[12 * k + 3 * v + 2] = MemoryUtil.memGetShort(a + 4) & 0xFFFF;
		}
		s.layer[k] = (byte) layer;
	}

	private static void key(Scratch s, int k, long q, int layer) {
		long[] c = s.c;
		for (int v = 0; v < 4; v++) {
			long a = q + v * 16L;
			int x = MemoryUtil.memGetShort(a) & 0xFFFF, y = MemoryUtil.memGetShort(a + 2) & 0xFFFF, z = MemoryUtil.memGetShort(a + 4) & 0xFFFF;
			s.raw[12 * k + 3 * v] = x;
			s.raw[12 * k + 3 * v + 1] = y;
			s.raw[12 * k + 3 * v + 2] = z;
			c[v] = x | (long) y << 16 | (long) z << 32;
		}
		// (sorting network for 4)
		long t;
		if (c[0] > c[1]) { t = c[0]; c[0] = c[1]; c[1] = t; }
		if (c[2] > c[3]) { t = c[2]; c[2] = c[3]; c[3] = t; }
		if (c[0] > c[2]) { t = c[0]; c[0] = c[2]; c[2] = t; }
		if (c[1] > c[3]) { t = c[1]; c[1] = c[3]; c[3] = t; }
		if (c[1] > c[2]) { t = c[1]; c[1] = c[2]; c[2] = t; }
		System.arraycopy(c, 0, s.k, 4 * k, 4);
		s.layer[k] = (byte) layer;
	}

	/**
	 * Certified simple (exact in the decoded positions): +1 / -1 its winding (the sign of the first non-zero component of
	 * n1 = (p1 - p0) x (p2 - p0)), 0 if not certified. Certified: n1 and n2 = (p3 - p2) x (p0 - p2) non-zero (both triangles
	 * nondegenerate), p3 on the plane of p0 p1 p2 (n1 . (p3 - p0) == 0), and n1, n2 pointing the same way (coplanar, so parallel: the
	 * sign of one non-zero component pair decides). The same way means p1 and p3 lie on opposite sides of the diagonal p0 p2: the
	 * triangles don't overlap and every pixel the quad covers faces as n1. Grid codes: integer arithmetic on the codes (decode is
	 * linear there); a table code: exact BigDecimal arithmetic on the decoded floats.
	 */
	static int certify(int[] raw, int o) {
		boolean grid = true;
		for (int i = 0; i < 12; i++) if (OwnPosTable.ON && raw[o + i] >= OwnPosTable.BASE) grid = false;
		if (grid) {
			long[] a = new long[3], b = new long[3], c = new long[3], d = new long[3];
			for (int k = 0; k < 3; k++) {
				a[k] = raw[o + 3 + k] - raw[o + k];
				b[k] = raw[o + 6 + k] - raw[o + k];
				c[k] = raw[o + 9 + k] - raw[o + 6 + k];
				d[k] = raw[o + k] - raw[o + 6 + k];
			}
			long[] n1 = cross(a, b), n2 = cross(c, d);
			long pl = 0;
			for (int k = 0; k < 3; k++) pl += n1[k] * (raw[o + 9 + k] - raw[o + k]);  // (|n1| < 2^33, |d| < 2^17: fits)
			return verdict(Long.signum(n1[0]), Long.signum(n1[1]), Long.signum(n1[2]), Long.signum(n2[0]), Long.signum(n2[1]), Long.signum(n2[2]), Long.signum(pl));
		}
		EXACT.increment();
		BigDecimal[] p = new BigDecimal[12];
		for (int i = 0; i < 12; i++) p[i] = new BigDecimal((double) OwnPosTable.decode(raw[o + i]));  // (exact: a float is a binary fraction)
		BigDecimal[] a = new BigDecimal[3], b = new BigDecimal[3], c = new BigDecimal[3], d = new BigDecimal[3];
		for (int k = 0; k < 3; k++) {
			a[k] = p[3 + k].subtract(p[k]);
			b[k] = p[6 + k].subtract(p[k]);
			c[k] = p[9 + k].subtract(p[6 + k]);
			d[k] = p[k].subtract(p[6 + k]);
		}
		BigDecimal[] n1 = cross(a, b), n2 = cross(c, d);
		BigDecimal pl = BigDecimal.ZERO;
		for (int k = 0; k < 3; k++) pl = pl.add(n1[k].multiply(p[9 + k].subtract(p[k])));
		return verdict(n1[0].signum(), n1[1].signum(), n1[2].signum(), n2[0].signum(), n2[1].signum(), n2[2].signum(), pl.signum());
	}

	private static int verdict(int a0, int a1, int a2, int b0, int b1, int b2, int plane) {
		if (a0 == 0 && a1 == 0 && a2 == 0 || b0 == 0 && b1 == 0 && b2 == 0 || plane != 0) return 0;
		// coplanar and nondegenerate: n1 and n2 are parallel, so their first non-zero components give the dot's sign
		int k1 = a0 != 0 ? a0 : a1 != 0 ? a1 : a2, k2 = a0 != 0 ? b0 : a1 != 0 ? b1 : b2;
		if (k2 == 0 || k1 != k2) return 0;  // (opposite facing: p1 and p3 on one side of the diagonal)
		return k1;
	}

	private static long[] cross(long[] a, long[] b) {
		return new long[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static BigDecimal[] cross(BigDecimal[] a, BigDecimal[] b) {
		return new BigDecimal[] {a[1].multiply(b[2]).subtract(a[2].multiply(b[1])), a[2].multiply(b[0]).subtract(a[0].multiply(b[2])),
			a[0].multiply(b[1]).subtract(a[1].multiply(b[0]))};
	}

	/**
	 * X_ONLY's groups: solid / cutout pairs that can tie, joined (union-find over the quads, a superset). Exact: a quad's sorted
	 * corner codes in a hash of the cutout quads', probed by every solid quad; the pair joins if its facing buckets can face the same
	 * way (equal, or either the any-facing bucket 6: identical corners in opposite axis buckets are back to back, never both drawn).
	 * Near (exactPos table codes, REVIEW-tiegroups2): each quad with a table code against the other layer's quads that could lie
	 * within TOL of it, by the mesher's buckets (OwnQuads: 0-5 exactly axis-planar, 6 the rest): a bucket b quad (axis b / 2), or a
	 * bucket 6 quad within 2 TOL of planar on an axis, against that axis's buckets and the bucket 6 quads; any other bucket 6 quad
	 * against bucket 6 only (a quad within TOL of it isn't axis-planar). Candidates by 1/8-block centroid cell, 27 cells.
	 */
	private static int[] groupsX(Scratch s, int n, int[] units) {
		long tg0 = System.nanoTime();
		int nc = 0;
		for (int q = 0; q < n; q++) if (s.layer[q] == 1) nc++;
		int[] out = {units[0], units[1], 0};
		if (nc == 0 || nc == n) return out;  // (one layer only: nothing to pair)
		int[] par = s.par(n);
		for (int i = 0; i < n; i++) par[i] = i;
		int cap = Integer.highestOneBit(Math.max(8, nc * 2)) * 2;
		if (s.tab.length < cap) s.tab = new int[cap];
		int[] tab = s.tab;
		Arrays.fill(tab, 0, cap, -1);
		for (int q = 0; q < n; q++) {
			if (s.layer[q] != 1) continue;
			int h = (int) (hash(s, q) & (cap - 1));
			while (tab[h] >= 0) h = (h + 1) & (cap - 1);
			tab[h] = q;
		}
		boolean joined = false;
		for (int q = 0; q < n; q++) {
			if (s.layer[q] != 0 || !s.keyed[q]) continue;
			for (int h = (int) (hash(s, q) & (cap - 1)); tab[h] >= 0; h = (h + 1) & (cap - 1)) {
				int c = tab[h];
				if (same(s, q, c) && facesAlike(s.bucket[q], s.bucket[c])) {
					union(par, q, c);
					joined = true;
				}
			}
		}
		long tn = System.nanoTime();
		EXACTX_NS.add(tn - tg0);
		if (OwnPosTable.ON) joined |= nearX(s, n, par);
		NEARX_NS.add(System.nanoTime() - tn);
		if (!joined) return out;
		// components: every joined quad by root (only joined ones: a cutout / solid pair or more)
		int m = 0;
		boolean[] root = s.root;
		Arrays.fill(root, 0, n, false);
		for (int q = 0; q < n; q++) {
			int r = find(par, q);
			if (r != q) root[r] = true;
		}
		long[] byRoot = s.byRoot;
		for (int q = 0; q < n; q++) {
			int r = find(par, q);
			if (r != q || root[q]) byRoot[m++] = (long) r << 21 | q;
		}
		Arrays.sort(byRoot, 0, m);
		int[] g = new int[3 + 2 * m + 8];
		int w = 3, groups = 0;
		for (int i = 0; i < m; ) {
			int j = i + 1;
			while (j < m && byRoot[j] >>> 21 == byRoot[i] >>> 21) j++;
			int cnt = j - i;
			if (cnt >= 2) {
				int[] mem = new int[cnt];
				for (int a = 0; a < cnt; a++) {
					int q = (int) (byRoot[i + a] & 0x1FFFFF);
					mem[a] = s.unit[q] << 6 | s.lq[q];
				}
				Arrays.sort(mem);
				g[w++] = cnt | X;
				System.arraycopy(mem, 0, g, w, cnt);
				w += cnt;
				groups++;
				MEMBERS.add(cnt);
			}
			i = j;
		}
		GROUPS.add(groups);
		X_GROUPS.add(groups);
		g[0] = units[0];
		g[1] = units[1];
		g[2] = groups;
		return Arrays.copyOf(g, groups == 0 ? 3 : w);
	}

	/** Two quads' facing buckets allow both to face the camera: equal, or either is the any-facing bucket. */
	static boolean facesAlike(int a, int b) {
		return a == b || a == 6 || b == 6;
	}

	/** The axis (0-2) on which quad q's corners lie within 2 TOL of one plane, by its bucket or its decoded corners; -1: none. */
	private static int planarAxis(Scratch s, int q, float[] p) {
		int b = s.bucket[q];
		if (b < 6) return b >> 1;
		decode(s.raw, 12 * q, p);
		for (int k = 0; k < 3; k++) {
			float lo = Math.min(Math.min(p[k], p[3 + k]), Math.min(p[6 + k], p[9 + k])), hi = Math.max(Math.max(p[k], p[3 + k]), Math.max(p[6 + k], p[9 + k]));
			if (hi - lo <= 2 * TOL) return k;
		}
		return -1;
	}

	/** X_ONLY's near pass: joins every solid / cutout pair within TOL where one of them has a table code. True if any joined. */
	private static boolean nearX(Scratch s, int n, int[] par) {
		boolean any = false;
		for (int i = 0; i < 12 * n && !any; i++) any = s.raw[i] >= OwnPosTable.BASE;
		if (!any) return false;
		float[] p = new float[12], pa = new float[12], pb = new float[12];
		int[] axis = new int[n];
		for (int q = 0; q < n; q++) axis[q] = s.bucket[q] < 6 ? s.bucket[q] >> 1 : -2;  // (-2: bucket 6, computed on use)
		// per layer, candidate cell lists by class (0-2 an axis: its buckets and planar bucket 6 quads; 3 every bucket 6 quad), built on use
		long[][][] cells = new long[2][4][];
		int[] clo = new int[3], chi = new int[3];
		boolean joined = false;
		for (int q = 0; q < n; q++) {
			boolean table = false;
			for (int i = 0; i < 12 && !table; i++) table = s.raw[12 * q + i] >= OwnPosTable.BASE;
			if (!table) continue;
			int other = 1 - s.layer[q];
			if (axis[q] == -2) axis[q] = planarAxis(s, q, p);
			// (an axis bucket quad: its axis's class, which holds the near-planar bucket 6 quads too; a planar bucket 6 quad: that and
			// every bucket 6 quad; any other: bucket 6 only)
			int[] classes = s.bucket[q] < 6 ? new int[] {axis[q]} : axis[q] >= 0 ? new int[] {axis[q], 3} : new int[] {3};
			decode(s.raw, 12 * q, pa);
			// (the cells its centroid +- TOL touches, as cellKey computes cells: a near quad's centroid is within TOL of it; 1 to 8)
			for (int a = 0; a < 3; a++) {
				double c = 0;
				for (int v = 0; v < 4; v++) c += OwnPosTable.decode(s.raw[12 * q + 3 * v + a]);
				clo[a] = (int) Math.floor((c / 4 - TOL * 1.0001) * 8);
				chi[a] = (int) Math.floor((c / 4 + TOL * 1.0001) * 8);
			}
			for (int cl : classes) {
				// (no quad of the other layer can be in the class: skipped without a list)
				int[] oc = s.count[other];
				if (cl == 3 ? oc[6] == 0 : oc[2 * cl] + oc[2 * cl + 1] + oc[6] == 0) continue;
				long[] list = cells[other][cl];
				if (list == null) list = cells[other][cl] = cellList(s, n, other, cl, axis, p);
				if (list.length == 0) continue;  // (no quad of the other layer in that class: e.g. a plant cross and no bucket 6 solid quad)
				for (int cx = clo[0]; cx <= chi[0]; cx++)
					for (int cy = clo[1]; cy <= chi[1]; cy++)
						for (int cz = clo[2]; cz <= chi[2]; cz++) {
							long key = (long) (cx + 256 & 511) << 18 | (long) (cy + 256 & 511) << 9 | (cz + 256 & 511);
							for (int k = lowerBound(list, key << 21); k < list.length && list[k] >>> 21 == key; k++) {
								int o = (int) (list[k] & 0x1FFFFF);
								if (find(par, o) == find(par, q)) continue;
								decode(s.raw, 12 * o, pb);
								if (!near(pa, pb)) continue;
								union(par, o, q);
								joined = true;
								NEAR.increment();
							}
						}
			}
		}
		return joined;
	}

	/** Layer l's quads of class cl (nearX), as sorted cellKey << 21 | index. */
	private static long[] cellList(Scratch s, int n, int l, int cl, int[] axis, float[] p) {
		long[] a = new long[n];
		int m = 0;
		for (int q = 0; q < n; q++) {
			if (s.layer[q] != l) continue;
			if (cl != 3 && s.bucket[q] == 6 && axis[q] == -2) axis[q] = planarAxis(s, q, p);
			boolean in = cl == 3 ? s.bucket[q] == 6 : s.bucket[q] < 6 ? s.bucket[q] >> 1 == cl : axis[q] == cl;
			if (in) a[m++] = cellKey(s.raw, 12 * q) << 21 | q;
		}
		a = Arrays.copyOf(a, m);
		Arrays.sort(a);
		return a;
	}

	private static long hash(Scratch s, int i) {
		long h = 0x9E3779B97F4A7C15L;
		for (int c = 0; c < 4; c++) {
			h ^= s.k[4 * i + c];
			h *= 0xBF58476D1CE4E5B9L;
			h ^= h >>> 31;
		}
		return h >>> 21;
	}

	/**
	 * The tolerance for positions the GPU may make equal (REVIEW-tiegroups2): the shader forms a world position in float32
	 * (local + (origin - camera block), then + the camera offset), so two exactly different positions within about 2 ulps of each other
	 * can come out equal. 2^-12 block is 2 ulps of float32 at |world - camera| up to 2048 blocks (render distance <= 64 chunks); a pair
	 * within it counts as a possible tie, corner for corner.
	 */
	static final double TOL = 1.0 / 4096;

	/** Two quads' corners within TOL of each other under some pairing of the corners (a, b: 12 coordinates each). */
	static boolean near(float[] a, float[] b) {
		double[] x = new double[12], y = new double[12];
		for (int i = 0; i < 12; i++) {
			x[i] = a[i];
			y[i] = b[i];
		}
		return near(x, y);
	}

	private static final int[][] PERMS = perms();

	private static int[][] perms() {
		int[][] r = new int[24][];
		int k = 0;
		for (int a = 0; a < 4; a++)
			for (int b = 0; b < 4; b++)
				for (int c = 0; c < 4; c++)
					for (int d = 0; d < 4; d++)
						if (a != b && a != c && a != d && b != c && b != d && c != d) r[k++] = new int[] {a, b, c, d};
		return r;
	}

	static boolean near(double[] a, double[] b) {
		for (int[] p : PERMS) {
			boolean ok = true;
			for (int v = 0; v < 4 && ok; v++)
				for (int k = 0; k < 3 && ok; k++) ok = Math.abs(a[3 * v + k] - b[3 * p[v] + k]) <= TOL;
			if (ok) return true;
		}
		return false;
	}

	/** The same 4 corners exactly, under some pairing (a, b: 12 coordinates each). */
	static boolean identical(double[] a, double[] b) {
		for (int[] p : PERMS) {
			boolean ok = true;
			for (int v = 0; v < 4 && ok; v++)
				for (int k = 0; k < 3 && ok; k++) ok = a[3 * v + k] == b[3 * p[v] + k];
			if (ok) return true;
		}
		return false;
	}

	private static void decode(int[] raw, int o, float[] p) {
		for (int i = 0; i < 12; i++) p[i] = OwnPosTable.decode(raw[o + i]);
	}

	/**
	 * The 1/8-block cell of the quad's centroid (section-local: positions decode to [-8, 24), cells -64 .. 191), packed 9 bits an axis
	 * with an offset of 256 ((x << 18 | y << 9 | z), 27 bits, so key << 21 | index fits a long).
	 */
	private static long cellKey(int[] raw, int o) {
		long key = 0;
		for (int k = 0; k < 3; k++) {
			double c = 0;
			for (int v = 0; v < 4; v++) c += OwnPosTable.decode(raw[o + 3 * v + k]);
			key = key << 9 | ((long) Math.floor(c / 4 * 8) + 256 & 511);
		}
		return key;
	}

	private static int lowerBound(long[] a, long key) {
		int lo = 0, hi = a.length;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (a[mid] < key) lo = mid + 1;
			else hi = mid;
		}
		return lo;
	}

	private static int find(int[] par, int i) {
		while (par[i] != i) {
			par[i] = par[par[i]];
			i = par[i];
		}
		return i;
	}

	private static void union(int[] par, int a, int b) {
		int ra = find(par, a), rb = find(par, b);
		if (ra != rb) par[Math.max(ra, rb)] = Math.min(ra, rb);
	}

	/** tieClose's ring values (terrain.metal: TIE_NONE, TIE_ALWAYS, TIE_RING_MAX). */
	static final int TIE_NONE = 0xFFFFFFFF, TIE_ALWAYS = 0xFFFFFFFE, TIE_RING_MAX = 16;

	/**
	 * tieClose: a mesh's opaque units' ring entries from its groups (g: build's layout, null = not known: all TIE_NONE). Units sharing
	 * a group are one component (union-find over the units u, record base + u). A component of 2 .. TIE_RING_MAX units is a ring in unit
	 * order (each entry the next member's record, the last the first's); every unit of a larger one is TIE_ALWAYS; the rest TIE_NONE.
	 */
	static int[] rings(int @org.jspecify.annotations.Nullable [] g, int units, int base) {
		int[] out = new int[units];
		Arrays.fill(out, TIE_NONE);
		if (g == null || g.length < 3 || g[2] == 0) return out;
		int[] par = new int[units];
		for (int u = 0; u < units; u++) par[u] = u;
		for (int i = 3; i < g.length; ) {
			int n = g[i] & 0xFFFF;
			if (!CLOSE_X || (g[i] & X) != 0) for (int j = 1; j < n; j++) union(par, g[i + 1] >>> 6, g[i + 1 + j] >>> 6);
			i += 1 + n;
		}
		int[] size = new int[units], first = new int[units], last = new int[units];
		for (int u = 0; u < units; u++) size[find(par, u)]++;
		Arrays.fill(first, -1);
		for (int u = 0; u < units; u++) {
			int r = find(par, u);
			if (size[r] < 2) continue;
			if (size[r] > TIE_RING_MAX) {
				out[u] = TIE_ALWAYS;
				continue;
			}
			if (first[r] < 0) first[r] = u;
			else out[last[r]] = base + u;
			last[r] = u;
		}
		for (int r = 0; r < units; r++) if (first[r] >= 0) out[last[r]] = base + first[r];  // (closes the ring)
		return out;
	}

	/**
	 * TOL's scope (REVIEW-tiegroups3 §D): the shader computes a coordinate as float32(local + (origin - cameraBlock)), then adds
	 * CameraOffset. While both results are below SCOPE in magnitude, each rounding moves it by at most half an ulp, 2^-14 there, so
	 * a quad's GPU coordinate is within 2^-13 of its exact one, and two quads whose positions alias (equal on the GPU) are within
	 * 2^-12 = TOL of each other: near() finds them (grid codes, 1/2048 apart, never alias).
	 */
	static final double SCOPE = 2048;
	/** The local (section-relative) extent of every mesh built so far, per axis lo x, y, z then hi x, y, z (never shrinks). */
	private static final float[] LOCAL = {0, 0, 0, 16, 16, 16};

	private static synchronized void extend(float[] ext) {
		for (int k = 0; k < 3; k++) {
			LOCAL[k] = Math.min(LOCAL[k], ext[k]);
			LOCAL[3 + k] = Math.max(LOCAL[3 + k], ext[3 + k]);
		}
	}

	static synchronized float[] local() {
		return LOCAL.clone();
	}

	/**
	 * Every coordinate the shader can compute is within SCOPE: sections with block origins in [minOrigin, maxOrigin] per axis,
	 * local positions in local (lo x, y, z, hi x, y, z), the camera block and CameraOffset (any value) as the frame sets them.
	 * Exact in double: the inputs are integers and floats. Both the sum before CameraOffset and the one after are checked.
	 */
	static boolean inScope(int[] minOrigin, int[] maxOrigin, float[] local, int[] camBlock, float[] camOffset) {
		for (int k = 0; k < 3; k++) {
			double lo = (double) minOrigin[k] - camBlock[k] + local[k], hi = (double) maxOrigin[k] - camBlock[k] + local[3 + k];
			double flo = lo + camOffset[k], fhi = hi + camOffset[k];
			if (Math.max(Math.max(Math.abs(lo), Math.abs(hi)), Math.max(Math.abs(flo), Math.abs(fhi))) >= SCOPE) return false;
		}
		return true;
	}

	/** tieFallback with TOL's scope: also true when the frame's coordinates can reach SCOPE (inScope false). */
	static boolean fallback(boolean on, long pairs, int unknown, boolean inScope) {
		return fallback(on, pairs, unknown) || !inScope;
	}

	/** tieFallback's contract (REVIEW-tiegroups2): true whenever per-quad selection must not rely on the groups. */
	static boolean fallback(boolean on, long pairs, int unknown) {
		return !on || pairs > 0 || unknown > 0;
	}

	private static boolean same(Scratch s, int a, int b) {
		for (int c = 0; c < 4; c++) if (s.k[4 * a + c] != s.k[4 * b + c]) return false;
		return true;
	}

	/** verify: quad id's codes in vertex order from the arena as the shader decodes it (qrec record and its stub, or the 64-byte quad). */
	static int[] arenaCodes(long arena, int id, boolean qrec) {
		return arenaCodesInto(new int[12], arena, id, qrec);
	}

	/** arenaCodes into r. */
	static int[] arenaCodesInto(int[] r, long arena, int id, boolean qrec) {
		for (int v = 0; v < 4; v++) {
			if (qrec) {
				long rec = arena + (long) id * OwnQrec.BYTES;
				int sel = MemoryUtil.memGetInt(rec);
				if (sel < 0) {
					long q = arena + (long) MemoryUtil.memGetInt(rec + 4) * OwnQuads.QUAD + v * 16L;
					for (int k = 0; k < 3; k++) r[3 * v + k] = MemoryUtil.memGetShort(q + k * 2) & 0xFFFF;
				} else {
					for (int k = 0; k < 3; k++) r[3 * v + k] = MemoryUtil.memGetInt(rec + 4 + k * 4) >>> (((sel >>> (3 * v + k)) & 1) * 16) & 0xFFFF;
				}
			} else {
				long q = arena + (long) id * OwnQuads.QUAD + v * 16L;
				for (int k = 0; k < 3; k++) r[3 * v + k] = MemoryUtil.memGetShort(q + k * 2) & 0xFFFF;
			}
		}
		return r;
	}

	/** The sorted corner key of 12 codes (vertex order). */
	static long[] sortedKey(int[] r) {
		long[] c = new long[4];
		for (int v = 0; v < 4; v++) c[v] = r[3 * v] | (long) r[3 * v + 1] << 16 | (long) r[3 * v + 2] << 32;
		Arrays.sort(c);
		return c;
	}

	/** What the directory needs of the installed sections (OwnTerrain; a test stubs it). */
	interface Lookup {
		/** The slot installed at section block origin x, y, z, or -1. */
		int slotAt(int x, int y, int z);

		/**
		 * How many of slot's opaque quads are tie candidates with a quad of world corners w (12), certify() value cert and layer
		 * (Dir.tie; with X_ONLY only quads of the other layer count).
		 */
		int matches(int slot, double[] w, int[] e, int o, int layer);
	}

	/**
	 * The cross-section directory (render thread). Its entries are the quads that can be near a quad of another section (build's edge
	 * records, the reaching quads): an overhang (beyond its section by more than TOL), or a quad within 2 TOL of a boundary plane
	 * that isn't an axis quad exactly in that plane facing out of its section. Each is looked up among the quads of every other
	 * section its corners reach (its box +- 2 TOL; Lookup.matches) while both are installed; the counts are kept per entry and
	 * target (pairs: the live total), so a section's install looks up the entries that reach it and its uninstall only subtracts.
	 * Why that finds every cross-section candidate: near() puts every corner of the one within TOL of the other's, so a pair of
	 * quads of two sections, neither overhanging, lies within 2 TOL of a plane the sections share. An axis quad exactly in that
	 * plane facing out of its section (every full block face on a section boundary) can be near a quad of the other section only
	 * if that one is near the plane too: if it faces the same way it faces into its own section (a reaching quad, which finds the
	 * pair), and if it is the opposite-facing axis quad in the same plane the two are back to back and never both drawn; anything
	 * else near the plane is a reaching quad. Degenerate quads (no area) draw nothing and aren't considered.
	 */
	static final class Dir {
		private final Lookup look;
		private int[] slot = new int[256], off = new int[256], ox = new int[256], oy = new int[256], oz = new int[256];
		private int[][] edges = new int[256][];
		/** per entry: its target sections (packed) and the matches counted against each while installed */
		private long[][] targets = new long[256][];
		private int[][] mcount = new int[256][];
		private int free = -1, top, live;
		private int[] nextFree = new int[256];
		/** entries by the section they reach (block origin, packed): entry id * 8 + target index */
		private final java.util.HashMap<Long, java.util.ArrayList<Integer>> byTarget = new java.util.HashMap<>();
		long pairs, ns, ops;

		Dir(Lookup look) {
			this.look = look;
		}

		int live() {
			return this.live;
		}

		/** Section slot s at block origin x, y, z installs a mesh with these edge records: their entry ids (then call installed). */
		int[] add(int s, int[] e, int x, int y, int z) {
			long t0 = System.nanoTime();
			int n = e.length / EDGE_INTS;
			int[] ids = new int[n];
			for (int i = 0; i < n; i++) {
				int o = i * EDGE_INTS, id = this.alloc();
				ids[i] = id;
				this.slot[id] = s;
				this.edges[id] = e;
				this.off[id] = o;
				this.ox[id] = x;
				this.oy[id] = y;
				this.oz[id] = z;
				double[] w = world(e, o, x, y, z);
				long[] t = reachAll(w, x, y, z);
				this.targets[id] = t;
				this.mcount[id] = new int[t.length];
				for (int k = 0; k < t.length; k++) {
					this.byTarget.computeIfAbsent(t[k], kk -> new java.util.ArrayList<>()).add(id * 8 + k);
					int ts = this.look.slotAt(unX(t[k]), unY(t[k]), unZ(t[k]));
					if (ts >= 0 && ts != s) {
						int m = this.look.matches(ts, w, e, o, layer(e, o));
						this.pairs += m;
						this.mcount[id][k] = m;
					}
				}
			}
			this.ns += System.nanoTime() - t0;
			this.ops += n;
			return ids;
		}

		/** Section slot s at block origin x, y, z now holds a mesh: the entries that reach it are counted against its quads. */
		void installed(int s, int x, int y, int z) {
			java.util.ArrayList<Integer> l = this.byTarget.get(pack(x, y, z));
			if (l == null) return;
			long t0 = System.nanoTime();
			for (int v : l) {
				int id = v >> 3, k = v & 7;
				if (this.slot[id] == s) continue;
				int m = this.look.matches(s, world(this.edges[id], this.off[id], this.ox[id], this.oy[id], this.oz[id]), this.edges[id], this.off[id],
					layer(this.edges[id], this.off[id]));
				this.pairs += m;
				this.mcount[id][k] += m;
			}
			this.ns += System.nanoTime() - t0;
		}

		/** Section slot s at block origin x, y, z is about to lose its mesh (before its edges are removed): the reverse of installed. */
		void uninstalling(int s, int x, int y, int z) {
			java.util.ArrayList<Integer> l = this.byTarget.get(pack(x, y, z));
			if (l == null) return;
			for (int v : l) {
				int id = v >> 3, k = v & 7;
				if (this.slot[id] == s) continue;
				this.pairs -= this.mcount[id][k];
				this.mcount[id][k] = 0;
			}
		}

		/** The entries of a section's mesh leave (it was replaced, cleared or released). */
		void remove(int[] ids) {
			long t0 = System.nanoTime();
			for (int id : ids) {
				long[] t = this.targets[id];
				for (int k = 0; k < t.length; k++) {
					java.util.ArrayList<Integer> l = this.byTarget.get(t[k]);
					if (l != null) {
						l.remove(Integer.valueOf(id * 8 + k));
						if (l.isEmpty()) this.byTarget.remove(t[k]);
					}
					this.pairs -= this.mcount[id][k];
				}
				this.targets[id] = null;
				this.mcount[id] = null;
				this.edges[id] = null;
				this.nextFree[id] = this.free;
				this.free = id;
				this.live--;
			}
			this.ns += System.nanoTime() - t0;
			this.ops += ids.length;
		}

		private int alloc() {
			int id;
			if (this.free >= 0) {
				id = this.free;
				this.free = this.nextFree[id];
			} else {
				if (this.top == this.slot.length) {
					int c = this.slot.length * 2;
					this.slot = Arrays.copyOf(this.slot, c);
					this.off = Arrays.copyOf(this.off, c);
					this.ox = Arrays.copyOf(this.ox, c);
					this.oy = Arrays.copyOf(this.oy, c);
					this.oz = Arrays.copyOf(this.oz, c);
					this.edges = Arrays.copyOf(this.edges, c);
					this.targets = Arrays.copyOf(this.targets, c);
					this.mcount = Arrays.copyOf(this.mcount, c);
					this.nextFree = Arrays.copyOf(this.nextFree, c);
				}
				id = this.top++;
			}
			this.live++;
			return id;
		}

		/** The sections other than (x, y, z) that world corners w's box, +- 2 TOL, reaches (packed block origins; 0 to 7). */
		static long[] reachAll(double[] w, int x, int y, int z) {
			int[] lo = new int[3], hi = new int[3];
			for (int k = 0; k < 3; k++) {
				double a = Math.min(Math.min(w[k], w[3 + k]), Math.min(w[6 + k], w[9 + k])), b = Math.max(Math.max(w[k], w[3 + k]), Math.max(w[6 + k], w[9 + k]));
				lo[k] = (int) Math.floor((a - 2 * TOL) / 16) * 16;
				hi[k] = (int) Math.floor((b + 2 * TOL) / 16) * 16;
			}
			long[] t = new long[8];
			int n = 0;
			for (int sx = lo[0]; sx <= hi[0]; sx += 16)
				for (int sy = lo[1]; sy <= hi[1]; sy += 16)
					for (int sz = lo[2]; sz <= hi[2]; sz += 16) {
						if (sx == x && sy == y && sz == z) continue;
						if (n < 8) t[n++] = pack(sx, sy, sz);
					}
			return Arrays.copyOf(t, n);
		}

		/** (certify values only) not a pair of certified simple quads with opposite winding; see tie for the full rule. */
		static boolean candidate(int a, int b) {
			return !(a != 0 && b != 0 && a != b);
		}

		/**
		 * A cross-section tie candidate: world corners near (TOL), unless they are identical and both quads are certified simple with
		 * opposite winding (back to back in one plane: never both front-facing). Near but not identical corners with opposite
		 * certify() signs stay candidates (REVIEW-tiegroups3 §B: two slightly tilted quads can both face the camera).
		 */
		static boolean tie(double[] a, int ca, double[] b, int cb) {
			return near(a, b) && (candidate(ca, cb) || !identical(a, b));
		}

		static int layer(int[] e, int o) {
			return e[o + 1] & 1;
		}

		/**
		 * Edge record o's mesher bucket (bits 4-6). A quad near it in another section faces the same way or is in bucket 6: an exactly
		 * axis-planar quad facing the opposite way lies in a parallel plane, back to back, never drawn at a pixel with it.
		 */
		static int bucket(int[] e, int o) {
			return e[o + 1] >>> 4 & 7;
		}

		/**
		 * Edge record o's certify() value: computed on first use (bits 1-2 CERT_LAZY until then) from its codes and kept in the record
		 * (render thread). Only a pair with identical corners needs it (tie), so most records never pay for the exact arithmetic.
		 */
		static int cert(int[] e, int o) {
			int v = (e[o + 1] >>> 1) & 3;
			if (v != CERT_LAZY) return v - 1;
			int[] raw = new int[12];
			for (int i = 0; i < 12; i++) raw[i] = (e[o + 2 + i / 2] >>> ((i & 1) * 16)) & 0xFFFF;
			int c = certify(raw, 0);
			e[o + 1] = (e[o + 1] & ~6) | (c + 1) << 1;
			return c;
		}

		/** World corners of edge record o of e in a section at block origin x, y, z (origin + decoded local, exact in double). */
		static double[] world(int[] e, int o, int x, int y, int z) {
			return worldInto(new double[12], e, o, x, y, z);
		}

		static double[] worldInto(double[] w, int[] e, int o, int x, int y, int z) {
			for (int i = 0; i < 12; i++) {
				int code = (e[o + 2 + i / 2] >>> ((i & 1) * 16)) & 0xFFFF, k = i % 3;
				w[i] = (k == 0 ? x : k == 1 ? y : z) + (double) OwnPosTable.decode(code);
			}
			return w;
		}





		static long pack(int x, int y, int z) {
			return ((long) (x >> 4) & 0x1FFFFF) << 42 | ((long) (y >> 4) & 0x1FFFFF) << 21 | (long) (z >> 4) & 0x1FFFFF;
		}

		private static int unX(long p) {
			return (int) (p << 1 >> 43) << 4;
		}

		private static int unY(long p) {
			return (int) (p << 22 >> 43) << 4;
		}

		private static int unZ(long p) {
			return (int) (p << 43 >> 43) << 4;
		}
	}
}
