// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * The far terrain's hand-off fed by our near terrain (-Dcadence.own.int.seam=true, with -Dcadence.own=true and -Dcadence.lod=true):
 * the far terrain (dev.cadence.metal.lod) steps back from a chunk exactly when our near terrain draws that whole chunk this frame,
 * and draws it the same frame it doesn't.
 *
 * Our cull draws a section iff it has a compile result in our section table and passes vanilla's distance rule (own_cull in
 * own/terrain.metal: the horizontal chunk distance from the camera's section, then |dy| <= the view distance) and the frustum.
 * The frustum doesn't matter here (what is out of view is out of view for both), so a chunk is masked iff the distance rule
 * passes and every section of it that can show holds a compile result: the sections from its lowest surface block (the
 * WORLD_SURFACE heightmap's minimum over the chunk) to its highest, all-air ones excepted (they draw nothing either way).
 * Sections wholly below the surface are left out because vanilla never compiles a sealed one (its occlusion graph never
 * reaches it); sections the graph hasn't reached yet (out of view) keep the chunk on the far terrain until they compile.
 * The table is the one this frame's cull read (events are applied at the top of drawOpaque, the far terrain runs at the end of
 * the same opaque phase), so there is no frame of lag and no hand-off delay to wait out.
 *
 * Incremental: a chunk column's readiness is recomputed only when one of its slots changed this frame (OwnTerrain.changed()),
 * all of them when the camera changes section vertically, the view distance changes or a frame was missed; the mask is
 * reassembled from the readiness (a bit test per chunk) only when a readiness changed or the camera changed section.
 *
 * The section fade-in: with -Dcadence.lod.fade=instant as well, sections appear without vanilla's fog fade while far terrain
 * draws (the far terrain already shows the ground there; fading in from the fog colour would flash a pale chunk over it).
 */
public final class OwnSeam {
	public static final boolean ON = Boolean.getBoolean("cadence.own") && Boolean.getBoolean("cadence.own.int.seam");
	private static final boolean FADE_INSTANT = ON && "instant".equals(System.getProperty("cadence.lod.fade", ""));
	/** -Dcadence.own.int.seamStats=true: the mask's cost and coverage, logged every 5 s. */
	private static final boolean STATS = Boolean.getBoolean("cadence.own.int.seamStats");

	/** The window the mask covers (chunk coordinates of its corner, its size and row stride in ints) and where far terrain can start. */
	public static final class Window {
		public int x0, z0, size, words;
		public double nearestFar;
	}

	private static final Window WINDOW = new Window();
	// the view area's grid this state is for, the camera section, the frame last seen
	private static int g = -1, h, minSy, vd, camSx = Integer.MIN_VALUE, camSy, camSz;
	private static long frame = Long.MIN_VALUE;
	private static double nearCamX = Double.NaN, nearCamZ;
	/** Per grid column (z * g + x, as the view area's storage): the chunk it was computed for and whether our terrain draws it whole. */
	private static int[] colCx = new int[0], colCz = new int[0];
	private static boolean[] colReady = new boolean[0], colSeen = new boolean[0];
	private static long statNanos, statCalls, statCols, statMasks, statLast;
	private static int statInRange, statMasked;

	private OwnSeam() {
	}

	/** Vanilla's section fade time, or 0 while far terrain draws with -Dcadence.lod.fade=instant. */
	public static long fadeMs(long vanilla) {
		return FADE_INSTANT && dev.cadence.metal.lod.Lod.noSectionFade() ? 0 : vanilla;
	}

	/**
	 * The far terrain's chunk mask for this frame in mask (a bit per chunk of a size x size window, rows of words ints; the caller
	 * keeps the array between frames), or null when our near terrain isn't drawing (then the caller keeps its own mask).
	 */
	public static @Nullable Window mask(double camX, double camY, double camZ, int[] mask, int maskMax) {
		OwnTerrain own = OwnTerrain.get();
		Minecraft mc = Minecraft.getInstance();
		ViewArea va = mc.levelRenderer == null ? null : mc.levelRenderer.viewArea();
		ClientLevel level = mc.level;
		if (own == null || va == null || level == null) return null;
		long t0 = STATS ? System.nanoTime() : 0;
		int nvd = va.getViewDistance(), ng = 2 * nvd + 1, nh = va.sectionCount(), nminSy = va.minSectionY();
		int sx = (int) Math.floor(camX) >> 4, sy = (int) Math.floor(camY) >> 4, sz = (int) Math.floor(camZ) >> 4;
		long f = own.frameNumber();
		boolean full = ng != g || nh != h || nminSy != minSy || sy != camSy || f != frame && f != frame + 1;
		boolean changed = full || sx != camSx || sz != camSz;
		int maxSy = va.maxSectionY();
		if (full) {
			g = ng;
			h = nh;
			minSy = nminSy;
			vd = nvd;
			camSy = sy;
			if (colReady.length != g * g) {
				colCx = new int[g * g];
				colCz = new int[g * g];
				colReady = new boolean[g * g];
				colSeen = new boolean[g * g];
			}
			for (int col = 0; col < g * g; col++) column(own, level, col, sx, sy, sz, maxSy);
		} else if (f != frame) {
			int[] slots = own.changed();
			int n = own.changedCount(), gh = g * h;
			for (int i = 0; i < n; i++) {
				int slot = slots[i], col = slot / gh * g + slot % g;
				if (col >= colSeen.length || colSeen[col]) continue;
				colSeen[col] = true;
				changed |= column(own, level, col, sx, sy, sz, maxSy);
			}
			for (int i = 0; i < n; i++) {
				int slot = slots[i], col = slot / gh * g + slot % g;
				if (col < colSeen.length) colSeen[col] = false;
			}
		}
		frame = f;
		Window w = WINDOW;
		if (changed) {
			int size = Math.min(maskMax, 2 * vd + 5), words = (size + 31) / 32;
			int x0 = sx - size / 2, z0 = sz - size / 2, inRange = 0, masked = 0;
			Arrays.fill(mask, 0);
			for (int mz = 0; mz < size; mz++) {
				int cz = z0 + mz, dz = Math.max(0, Math.abs(cz - sz) - 1);
				if (dz >= vd) continue;
				int zi = Math.floorMod(cz, g) * g;
				for (int mx = 0; mx < size; mx++) {
					int cx = x0 + mx, dx = Math.max(0, Math.abs(cx - sx) - 1);
					if (dx * dx + dz * dz >= vd * vd) continue;
					inRange++;
					int col = zi + Math.floorMod(cx, g);
					if (!colReady[col] || colCx[col] != cx || colCz[col] != cz) continue;
					masked++;
					int bit = mz * words * 32 + mx;
					mask[bit >> 5] |= 1 << (bit & 31);
				}
			}
			w.x0 = x0;
			w.z0 = z0;
			w.size = size;
			w.words = words;
			camSx = sx;
			camSz = sz;
			statMasks++;
			statInRange = inRange;
			statMasked = masked;
		}
		if (changed || Math.abs(camX - nearCamX) >= 0.5 || Math.abs(camZ - nearCamZ) >= 0.5) {
			// where far terrain can start: the nearest chunk of the window the near terrain doesn't draw (else the window's edge)
			double near = (w.size / 2 - 1) * 16.0;
			for (int mz = 0; mz < w.size; mz++) {
				int oz = (w.z0 + mz) * 16;
				double dz = Math.max(0, Math.max(oz - camZ, camZ - (oz + 16)));
				if (dz >= near) continue;
				for (int mx = 0; mx < w.size; mx++) {
					int bit = mz * w.words * 32 + mx;
					if ((mask[bit >> 5] >>> (bit & 31) & 1) != 0) continue;
					int ox = (w.x0 + mx) * 16;
					double dx = Math.max(0, Math.max(ox - camX, camX - (ox + 16)));
					near = Math.min(near, Math.sqrt(dx * dx + dz * dz));
				}
			}
			w.nearestFar = near;
			nearCamX = camX;
			nearCamZ = camZ;
		}
		if (STATS) {
			statNanos += System.nanoTime() - t0;
			statCalls++;
			long now = System.nanoTime();
			if (statLast == 0) statLast = now;
			if (now - statLast >= 5_000_000_000L) {
				System.out.printf("cadence-own seam: %d frames, %.4f ms a frame, %d columns recomputed, %d mask builds; chunks in range %d, drawn whole %d, nearest far %.1f%n",
					statCalls, statNanos / 1e6 / Math.max(1, statCalls), statCols, statMasks, statInRange, statMasked, w.nearestFar);
				statNanos = statCalls = statCols = statMasks = 0;
				statLast = now;
			}
		}
		return w;
	}

	/**
	 * Recomputes grid column col (the chunk the view area keeps there for a camera in section sx, sz): whether every section of it
	 * that can show holds a compile result. Returns whether its state changed.
	 */
	private static boolean column(OwnTerrain own, ClientLevel level, int col, int sx, int sy, int sz, int maxSy) {
		statCols++;
		int xg = col % g, zg = col / g;
		int cx = sx - vd + Math.floorMod(xg - (sx - vd), g), cz = sz - vd + Math.floorMod(zg - (sz - vd), g);
		boolean ready = false;
		LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
		if (chunk != null) {
			int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
			for (int bz = 0; bz < 16; bz++) {
				for (int bx = 0; bx < 16; bx++) {
					int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
					lo = Math.min(lo, top);
					hi = Math.max(hi, top);
				}
			}
			// (the near terrain draws sections within vd of the camera's vertically too, OwnTerrain's cull: a surface outside that
			// range isn't drawn, so the column stays the far terrain's. It counted as drawn whole, and from high up the land
			// under the camera was a hole)
			ready = (lo >> 4) >= sy - vd && (hi >> 4) <= sy + vd;
			int ylo = Math.max(Math.max(minSy, sy - vd), lo >> 4), yhi = Math.min(Math.min(maxSy, sy + vd), hi >> 4);
			for (int y = ylo; y <= yhi && ready; y++) {
				int index = y - minSy;
				if (chunk.getSection(index).hasOnlyAir()) continue;
				ready = own.compiledAt((zg * h + index) * g + xg, cx << 4, y << 4, cz << 4);
			}
		}
		boolean was = colReady[col] && colCx[col] == cx && colCz[col] == cz;
		colReady[col] = ready;
		colCx[col] = cx;
		colCz[col] = cz;
		return was != ready;
	}
}
