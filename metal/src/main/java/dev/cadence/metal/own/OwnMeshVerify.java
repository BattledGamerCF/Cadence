// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

/**
 * The mesh digest check (-Dcadence.own.mesh=verify). After vanilla's compile of a section, our mesher runs on the same region and,
 * per layer (solid, cutout), its arena bytes and draw units are compared with what OwnTerrain.store makes of vanilla's MeshData
 * (its own facing and compact functions, the same stable bucket order and unit split). Logs every 5 s: sections, layers, quads,
 * mismatches, and a scene digest of each side (an order-independent sum of per-layer hashes; equal iff every layer matched).
 */
public final class OwnMeshVerify {
	private static final AtomicLong SECTIONS = new AtomicLong(), LAYERS = new AtomicLong(), QUADS = new AtomicLong(), BAD = new AtomicLong(),
		BAD_COUNT = new AtomicLong(), BAD_BYTES = new AtomicLong(), BAD_RUNS = new AtomicLong(), BAD_LIGHT = new AtomicLong(), DIGEST_VANILLA = new AtomicLong(), DIGEST_OWN = new AtomicLong();
	private static volatile long logAt;
	private static final ThreadLocal<long[]> REF = ThreadLocal.withInitial(() -> new long[]{0, 0, 0, 0});

	private OwnMeshVerify() {
	}

	/** Our mesher on the region, before vanilla's compile (so tint cache misses go through our blend and get checked). */
	public static void before(boolean ambientOcclusion, boolean cutoutLeaves, BlockStateModelSet blockModelSet, FluidStateModelSet fluidModelSet,
		BlockColors blockColors, SectionPos sectionPos, RenderSectionRegion region, SectionBufferBuilderPack builders) {
		OwnQuads quads = OwnQuads.get();
		SectionCompiler.Results ours = new SectionCompiler.Results();
		boolean shade = OwnMaterials.ON, was = shade && OwnMaterials.compiling();
		if (shade) OwnMaterials.compiling(true);
		try {
			OwnMesher.mesh(ambientOcclusion, cutoutLeaves, blockModelSet, fluidModelSet, blockColors, sectionPos, region, builders, ours, quads, null);
		} finally {
			if (shade) OwnMaterials.compiling(was);  // vanilla's compile may already be bracketed (hook order)
		}
		OURS.set(ours);
	}

	private static final ThreadLocal<SectionCompiler.Results> OURS = new ThreadLocal<>();
	private static final long[] SUB_TALLY = new long[6];
	private static final AtomicLong SUB_BAD = new AtomicLong();
	private static final AtomicLong FACINGS = new AtomicLong(), PURE_UNITS = new AtomicLong(), PURE_QUADS = new AtomicLong(), ANY_UNITS = new AtomicLong(), ANY_QUADS = new AtomicLong();
	private static final AtomicLong UNITS = new AtomicLong(), UNIT_QUADS = new AtomicLong(), UNIT_AREA = new AtomicLong(), UNIT_ALONG = new AtomicLong();
	private static final AtomicLong VIS_BAD = new AtomicLong(), MATS = new AtomicLong(), MAT_BAD = new AtomicLong();

	/** Our quads (from before()) against vanilla's compile result. */
	public static void check(SectionPos sectionPos, SectionCompiler.Results vanilla) {
		OwnQuads quads = OwnQuads.peek();
		SectionCompiler.Results ourResults = OURS.get();
		if (ourResults != null) {
			// the section's visibility set (cave culling graph input), face pair by face pair
			net.minecraft.core.Direction[] d = net.minecraft.core.Direction.values();
			boolean same = true;
			for (net.minecraft.core.Direction a : d) for (net.minecraft.core.Direction b : d) same &= ourResults.visibilitySet.visibilityBetween(a, b) == vanilla.visibilitySet.visibilityBetween(a, b);
			if (!same && VIS_BAD.incrementAndGet() <= 5) System.out.println("cadence-own mesh verify: VISIBILITY MISMATCH " + sectionPos);
		}
		SECTIONS.incrementAndGet();
		for (int l = 0; l < 2; l++) {
			ChunkSectionLayer layer = l == 0 ? ChunkSectionLayer.SOLID : ChunkSectionLayer.CUTOUT;
			MeshData mesh = vanilla.renderedLayers.get(layer);
			OwnQuads.Layer ours = quads.layers[l];
			int n = mesh == null ? 0 : mesh.vertexBuffer().remaining() / OwnTerrain.QUAD_BYTES;
			if (n == 0 && ours.total() == 0) continue;
			LAYERS.incrementAndGet();
			QUADS.addAndGet(n);
			long seed = sectionPos.asLong() * 31 + l;
			// ours: bucket-ordered bytes
			long ownAddr = scratch(1, (long) ours.total() * OwnQuads.QUAD);
			ours.copyTo(ownAddr);
			int[] ownRuns = ours.runs(OwnTerrain.RUN);
			// unit shape statistics (sizing for the per-unit occlusion test): count, quads, box area across / extent along the facing
			for (int r = 0; r < ownRuns.length; r += OwnTerrain.RUN_INTS) {
				int b = ownRuns[r + 2], lo = ownRuns[r + 4], hi = ownRuns[r + 5];
				int ex = (hi & 255) - (lo & 255), ey = (hi >>> 8 & 255) - (lo >>> 8 & 255), ez = (hi >>> 16) - (lo >>> 16);
				int along = b == 6 ? 0 : b < 2 ? ex : b < 4 ? ey : ez;
				long area = b == 6 ? (long) ex * ey * ez : b < 2 ? (long) ey * ez : b < 4 ? (long) ex * ez : (long) ex * ey;
				UNITS.incrementAndGet();
				UNIT_QUADS.addAndGet(ownRuns[r + 1]);
				UNIT_AREA.addAndGet(area);
				UNIT_ALONG.addAndGet(along);
				// facing purity: distinct facing classes among the unit's quads (axis buckets: 1 by construction)
				int first = ownRuns[r], c = ownRuns[r + 1], base = 0;
				for (int bb = 0; bb < b; bb++) base += ours.count[bb];
				int mask = 0;
				for (int i = first - base; i < first - base + c; i++) mask |= 1 << ours.cls[b][i];
				int distinct = Integer.bitCount(mask);
				FACINGS.addAndGet(distinct);
				if (distinct == 1) {
					PURE_UNITS.incrementAndGet();
					PURE_QUADS.addAndGet(c);
				}
				if (b == 6) {
					ANY_UNITS.incrementAndGet();
					ANY_QUADS.addAndGet(c);
				}
			}
			long ownHash = hash(seed, ownAddr, (long) ours.total() * OwnQuads.QUAD, ownRuns);
			// vanilla through store()'s transform
			long refAddr = scratch(0, (long) n * OwnQuads.QUAD);
			int[] refRuns = n == 0 ? new int[0] : reference(mesh.vertexBuffer(), n, refAddr);
			long refHash = hash(seed, refAddr, (long) n * OwnQuads.QUAD, refRuns);
			DIGEST_VANILLA.addAndGet(refHash);
			DIGEST_OWN.addAndGet(ownHash);
			boolean countOk = n == ours.total();
			boolean bytesOk = countOk && equal(refAddr, ownAddr, (long) n * OwnQuads.QUAD);
			boolean runsOk = Arrays.equals(refRuns, ownRuns);
			if (OwnQuads.SUBBOX && n > 0 && n == ours.total() && runsOk) {
				// sub-boxes: recomputed from vanilla's floats in the reference's arena order, and every quad inside A or B
				int[] ord = REF_ORDER.get();
				long vsrc = MemoryUtil.memAddress(mesh.vertexBuffer());
				int[] rlo = new int[n], rhi = new int[n];
				for (int i = 0; i < n; i++) {
					long qa = vsrc + (long) ord[i] * OwnTerrain.QUAD_BYTES;
					float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
					for (int v = 0; v < 4; v++) {
						long va = qa + (long) v * OwnTerrain.VERTEX_BYTES;
						float x = MemoryUtil.memGetFloat(va), y = MemoryUtil.memGetFloat(va + 4), z = MemoryUtil.memGetFloat(va + 8);
						minX = Math.min(minX, x);
						minY = Math.min(minY, y);
						minZ = Math.min(minZ, z);
						maxX = Math.max(maxX, x);
						maxY = Math.max(maxY, y);
						maxZ = Math.max(maxZ, z);
					}
					rlo[i] = OwnTerrain.box(minX, true) | OwnTerrain.box(minY, true) << 8 | OwnTerrain.box(minZ, true) << 16;
					rhi[i] = OwnTerrain.box(maxX, false) | OwnTerrain.box(maxY, false) << 8 | OwnTerrain.box(maxZ, false) << 16;
				}
				long[] tally = new long[6];
				int[] refSubs = OwnQuads.subboxes(refRuns, rlo, rhi, tally);
				synchronized (SUB_TALLY) {
					for (int k = 0; k < 6; k++) SUB_TALLY[k] += tally[k];
				}
				int[][] boxes = ours.arenaBoxes();
				int[] ownSubs = OwnQuads.subboxes(ownRuns, boxes[0], boxes[1], null);
				boolean subsOk = Arrays.equals(refSubs, ownSubs);
				// containment: each quad's box inside A or inside B (decoded as the test side will)
				for (int u = 0; u < ownRuns.length / OwnTerrain.RUN_INTS && subsOk; u++) {
					int r0 = u * OwnTerrain.RUN_INTS;
					int[] ul = {ownRuns[r0 + 4] & 255, ownRuns[r0 + 4] >>> 8 & 255, ownRuns[r0 + 4] >>> 16 & 255};
					int[] uh = {ownRuns[r0 + 5] & 255, ownRuns[r0 + 5] >>> 8 & 255, ownRuns[r0 + 5] >>> 16 & 255};
					int[][] d = OwnQuads.decode(ownSubs[2 * u], ownSubs[2 * u + 1], ul, uh);
					for (int i = ownRuns[r0]; i < ownRuns[r0] + ownRuns[r0 + 1]; i++) {
						boolean inA = true, inB = true;
						for (int a = 0; a < 3; a++) {
							int ql = boxes[0][i] >>> (8 * a) & 255, qh = boxes[1][i] >>> (8 * a) & 255;
							inA &= ql >= d[0][a] && qh <= d[1][a];
							inB &= ql >= d[2][a] && qh <= d[3][a];
						}
						if (!inA && !inB) {
							subsOk = false;
							break;
						}
					}
				}
				if (!subsOk && SUB_BAD.incrementAndGet() <= 5) System.out.println("cadence-own mesh verify: SUBBOX MISMATCH " + sectionPos + " " + layer);
			}
			if (OwnMaterials.ON && n > 0 && n == ours.total()) {
				long refMat = scratch(2, n), ownMat = scratch(3, n);
				reference(mesh.vertexBuffer(), n, refAddr, refMat);
				ours.writeMaterials(ownMat, OwnMaterials.grid());
				MATS.addAndGet(n);
				for (int i = 0; i < n; i++) {
					if (MemoryUtil.memGetByte(refMat + i) != MemoryUtil.memGetByte(ownMat + i)) {
						if (MAT_BAD.incrementAndGet() <= 5) System.out.println("cadence-own mesh verify: MATERIAL MISMATCH " + sectionPos + " " + layer + " quad " + i);
						break;
					}
				}
			}
			if (!countOk || !bytesOk || !runsOk) {
				long bad = BAD.incrementAndGet();
				if (!countOk) BAD_COUNT.incrementAndGet();
				else if (!bytesOk) BAD_BYTES.incrementAndGet();
				if (!runsOk) BAD_RUNS.incrementAndGet();
				// which vertex fields differ: light only means the live light engine changed between the two meshings (chunks loading)
				String fields = countOk && !bytesOk ? fields(refAddr, ownAddr, n) : "";
				if (fields.equals("light")) BAD_LIGHT.incrementAndGet();
				if (bad <= 10) System.out.println("cadence-own mesh verify: MISMATCH " + sectionPos + " " + layer + " vanilla " + n + " quads, ours " + ours.total()
					+ (countOk && !bytesOk ? " (first byte " + firstDiff(refAddr, ownAddr, (long) n * OwnQuads.QUAD) + ", fields " + fields + ")" : "") + (runsOk ? "" : " (units differ)"));
			}
		}
		long now = System.nanoTime();
		if (now > logAt) {
			logAt = now + 5_000_000_000L;
			OwnTintHook.log();
			long u = Math.max(1, UNITS.get());
			System.out.printf("cadence-own mesh verify: units %d (%s), %.1f quads/unit, box area across the facing %.1f blocks^2 (any-facing: volume), extent along it %.2f blocks%n",
				UNITS.get(), System.getProperty("cadence.own.mesh.order", "emission"), UNIT_QUADS.get() / (double) u, UNIT_AREA.get() / (double) u, UNIT_ALONG.get() / (double) u);
			if (OwnQuads.SUBBOX) {
				long[] c;
				synchronized (SUB_TALLY) {
					c = SUB_TALLY.clone();
				}
				long un = Math.max(1, c[0]);
				System.out.printf("cadence-own mesh verify: sub-boxes: %d units, %.1f%% split; volume union %.2f -> A+B %.2f blocks^3/unit (%.1f%%), surface union %.2f -> A+B %.2f blocks^2/unit (%.1f%%); mismatched %d%n",
					c[0], c[1] * 100.0 / un, c[2] / (double) un, c[3] / (double) un, c[3] * 100.0 / Math.max(1, c[2]), c[4] / (double) un, c[5] / (double) un,
					c[5] * 100.0 / Math.max(1, c[4]), SUB_BAD.get());
			}
			long q = Math.max(1, UNIT_QUADS.get());
			System.out.printf("cadence-own mesh verify: facing purity: %.2f%% of units single-facing, %.3f facings/unit, %.2f%% of quads in single-facing units; any-facing bucket %.2f%% of units, %.2f%% of quads%n",
				PURE_UNITS.get() * 100.0 / u, FACINGS.get() / (double) u, PURE_QUADS.get() * 100.0 / q, ANY_UNITS.get() * 100.0 / u, ANY_QUADS.get() * 100.0 / q);
			System.out.println("cadence-own mesh verify: visibility sets mismatched " + VIS_BAD.get() + (OwnMaterials.ON ? ", material bytes checked " + MATS.get() + ", layers mismatched " + MAT_BAD.get() : ""));
			System.out.printf("cadence-own mesh verify: %d sections, %d layers, %d quads, %d mismatched (count %d, bytes %d [light only %d], units %d), digest vanilla %016x ours %016x%n",
				SECTIONS.get(), LAYERS.get(), QUADS.get(), BAD.get(), BAD_COUNT.get(), BAD_BYTES.get(), BAD_LIGHT.get(), BAD_RUNS.get(), DIGEST_VANILLA.get(), DIGEST_OWN.get());
		}
	}

	/** OwnTerrain.store's transform of n BLOCK quads at src into dst (64 bytes a quad); returns the draw units. */
	static int[] reference(ByteBuffer vertices, int n, long dst) {
		return reference(vertices, n, dst, 0);
	}

	/** As above; with matDst != 0 also storeMaterials' byte per quad (native shading) in the same order. */
	/** The last reference()'s final quad order (arena position -> vanilla quad index), for the sub-box check. */
	private static final ThreadLocal<int[]> REF_ORDER = new ThreadLocal<>();

	static int[] reference(ByteBuffer vertices, int n, long dst, long matDst) {
		long src = MemoryUtil.memAddress(vertices);
		byte[] bucket = new byte[n];
		float[] plane = new float[n];
		int[] counts = new int[OwnQuads.BUCKETS];
		for (int q = 0; q < n; q++) {
			int b = OwnTerrain.facing(src + (long) q * OwnTerrain.QUAD_BYTES, plane, q);
			bucket[q] = (byte) b;
			counts[b]++;
		}
		int[] order = new int[n], at = new int[OwnQuads.BUCKETS];
		for (int b = 1; b < OwnQuads.BUCKETS; b++) at[b] = at[b - 1] + counts[b - 1];
		int[] firsts = at.clone();
		for (int q = 0; q < n; q++) order[at[bucket[q]]++] = q;
		if (OwnQuads.ORDER) {
			// the same key as OwnQuads.Layer.order, from vanilla's floats: plane, box minimum cell, emission index in the bucket
			for (int b = 0; b < OwnQuads.BUCKETS; b++) {
				int f = firsts[b], c = counts[b];
				long[] k = new long[c];
				for (int i = 0; i < c; i++) {
					int q = order[f + i];
					long qa = src + (long) q * OwnTerrain.QUAD_BYTES;
					float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
					for (int v = 0; v < 4; v++) {
						long va = qa + (long) v * OwnTerrain.VERTEX_BYTES;
						minX = Math.min(minX, MemoryUtil.memGetFloat(va));
						minY = Math.min(minY, MemoryUtil.memGetFloat(va + 4));
						minZ = Math.min(minZ, MemoryUtil.memGetFloat(va + 8));
					}
					int lo = OwnTerrain.box(minX, true) | OwnTerrain.box(minY, true) << 8 | OwnTerrain.box(minZ, true) << 16;
					k[i] = (OwnQuads.key(b, plane[q], lo, i) & ~0xFFFFFFL) | i;
				}
				java.util.Arrays.sort(k);
				int[] seg = new int[c];
				for (int i = 0; i < c; i++) seg[i] = order[f + (int) (k[i] & 0xFFFFFF)];
				System.arraycopy(seg, 0, order, f, c);
			}
		}
		for (int i = 0; i < n; i++) {
			long qs = src + (long) order[i] * OwnTerrain.QUAD_BYTES, qd = dst + (long) i * OwnQuads.QUAD;
			for (int v = 0; v < 4; v++) OwnTerrain.compact(qs + (long) v * OwnTerrain.VERTEX_BYTES, qd + v * 16L);
		}
		if (matDst != 0) {
			// OwnTerrain.storeMaterials, restated for the check
			byte[] grid = OwnMaterials.grid();
			for (int i = 0; i < n; i++) {
				int q = order[i];
				long a = src + (long) q * OwnTerrain.QUAD_BYTES;
				float cx = 0, cy = 0, cz = 0, ylo = Float.POSITIVE_INFINITY, yhi = Float.NEGATIVE_INFINITY;
				for (int v = 0; v < 4; v++) {
					long va = a + (long) v * OwnTerrain.VERTEX_BYTES;
					float y = MemoryUtil.memGetFloat(va + 4);
					cx += MemoryUtil.memGetFloat(va);
					cy += y;
					cz += MemoryUtil.memGetFloat(va + 8);
					ylo = Math.min(ylo, y);
					yhi = Math.max(yhi, y);
				}
				cx *= 0.25F;
				cy *= 0.25F;
				cz *= 0.25F;
				final float e = 1.0F / 32.0F;
				switch (bucket[q]) {
					case 0 -> cx -= e;
					case 1 -> cx += e;
					case 2 -> cy -= e;
					case 3 -> cy += e;
					case 4 -> cz -= e;
					case 5 -> cz += e;
					default -> {
					}
				}
				int bx = Math.clamp((int) Math.floor(cx), 0, 15), by = Math.clamp((int) Math.floor(cy), 0, 15), bz = Math.clamp((int) Math.floor(cz), 0, 15);
				int m = grid[by << 8 | bz << 4 | bx] & 15;
				float mid = (ylo + yhi) * 0.5F + 1e-4F;
				for (int v = 0; v < 4; v++) if (MemoryUtil.memGetFloat(a + (long) v * OwnTerrain.VERTEX_BYTES + 4) > mid) m |= 16 << v;
				MemoryUtil.memPutByte(matDst + i, (byte) m);
			}
		}
		REF_ORDER.set(order);
		int run = OwnTerrain.RUN, runCount = 0;
		// unit boundaries by OwnQuads.units (CLUSTER cuts at plane / cell changes), from this side's own plane and box values
		int[][] starts = new int[OwnQuads.BUCKETS][];
		int[] unitCount = new int[OwnQuads.BUCKETS];
		for (int b = 0; b < OwnQuads.BUCKETS; b++) {
			int f = firsts[b], c = counts[b];
			float[] pl = new float[c];
			int[] lob = new int[c];
			for (int i = 0; i < c; i++) {
				int q = order[f + i];
				pl[i] = plane[q];
				long qa = src + (long) q * OwnTerrain.QUAD_BYTES;
				float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
				for (int v = 0; v < 4; v++) {
					long va = qa + (long) v * OwnTerrain.VERTEX_BYTES;
					minX = Math.min(minX, MemoryUtil.memGetFloat(va));
					minY = Math.min(minY, MemoryUtil.memGetFloat(va + 4));
					minZ = Math.min(minZ, MemoryUtil.memGetFloat(va + 8));
				}
				lob[i] = OwnTerrain.box(minX, true) | OwnTerrain.box(minY, true) << 8 | OwnTerrain.box(minZ, true) << 16;
			}
			starts[b] = new int[c + 1];
			unitCount[b] = OwnQuads.units(b, c, run, pl, lob, starts[b]);
			runCount += unitCount[b];
		}
		int[] runs = new int[runCount * OwnTerrain.RUN_INTS];
		int r = 0;
		for (int b = 0; b < OwnQuads.BUCKETS; b++) {
			for (int u = 0; u < unitCount[b]; u++) {
				int first = firsts[b] + starts[b][u], c = starts[b][u + 1] - starts[b][u];
				float bound = (b & 1) == 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
				for (int i = first; i < first + c; i++) bound = (b & 1) == 0 ? Math.min(bound, plane[order[i]]) : Math.max(bound, plane[order[i]]);
				int fixed = b == 6 ? 0 : (b & 1) == 0 ? (int) Math.floor(bound * 256) : (int) Math.ceil(bound * 256);
				float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
				for (int i = first; i < first + c; i++) {
					long qa = src + (long) order[i] * OwnTerrain.QUAD_BYTES;
					for (int v = 0; v < 4; v++) {
						long va = qa + (long) v * OwnTerrain.VERTEX_BYTES;
						float x = MemoryUtil.memGetFloat(va), y = MemoryUtil.memGetFloat(va + 4), z = MemoryUtil.memGetFloat(va + 8);
						minX = Math.min(minX, x);
						minY = Math.min(minY, y);
						minZ = Math.min(minZ, z);
						maxX = Math.max(maxX, x);
						maxY = Math.max(maxY, y);
						maxZ = Math.max(maxZ, z);
					}
				}
				runs[r++] = first;
				runs[r++] = c;
				runs[r++] = b;
				runs[r++] = fixed;
				runs[r++] = OwnTerrain.box(minX, true) | OwnTerrain.box(minY, true) << 8 | OwnTerrain.box(minZ, true) << 16;
				runs[r++] = OwnTerrain.box(maxX, false) | OwnTerrain.box(maxY, false) << 8 | OwnTerrain.box(maxZ, false) << 16;
			}
		}
		return runs;
	}

	private static long scratch(int i, long bytes) {
		long[] s = REF.get();
		long cap = s[i] == 0 ? 0 : MemoryUtil.memGetLong(s[i]);
		if (cap < bytes + 8) {
			long size = Math.max(bytes + 8, 1 << 20);
			s[i] = s[i] == 0 ? MemoryUtil.nmemAlloc(size) : MemoryUtil.nmemRealloc(s[i], size);
			MemoryUtil.memPutLong(s[i], size);
		}
		return s[i] + 8;
	}

	private static boolean equal(long a, long b, long bytes) {
		for (long i = 0; i < bytes; i += 8) if (MemoryUtil.memGetLong(a + i) != MemoryUtil.memGetLong(b + i)) return false;
		return true;
	}

	private static long firstDiff(long a, long b, long bytes) {
		for (long i = 0; i < bytes; i++) if (MemoryUtil.memGetByte(a + i) != MemoryUtil.memGetByte(b + i)) return i;
		return -1;
	}

	/** The vertex fields (pos, light, colour, uv) that differ anywhere in n quads. */
	private static String fields(long a, long b, int n) {
		boolean pos = false, light = false, color = false, uv = false;
		for (long v = 0; v < 4L * n; v++) {
			long x = MemoryUtil.memGetLong(a + v * 16) ^ MemoryUtil.memGetLong(b + v * 16), y = MemoryUtil.memGetLong(a + v * 16 + 8) ^ MemoryUtil.memGetLong(b + v * 16 + 8);
			pos |= (x & 0xFFFFFFFFFFFFL) != 0;
			light |= (x >>> 48) != 0;
			color |= (y & 0xFFFFFFFFL) != 0;
			uv |= (y >>> 32) != 0;
		}
		return String.join("+", java.util.stream.Stream.of(pos ? "pos" : null, light ? "light" : null, color ? "colour" : null, uv ? "uv" : null).filter(java.util.Objects::nonNull).toList());
	}

	private static long hash(long seed, long addr, long bytes, int[] runs) {
		long h = seed * 0x9E3779B97F4A7C15L;
		for (long i = 0; i < bytes; i += 8) h = (h ^ MemoryUtil.memGetLong(addr + i)) * 0x100000001B3L + 0x632BE59BD9B4E019L;
		for (int r : runs) h = (h ^ r) * 0x100000001B3L;
		return h ^ h >>> 29;
	}
}
