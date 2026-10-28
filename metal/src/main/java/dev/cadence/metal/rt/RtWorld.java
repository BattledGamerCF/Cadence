package dev.cadence.metal.rt;

import dev.cadence.metal.lod.LodColors;
import java.util.ArrayDeque;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.lwjgl.system.MemoryUtil;

/**
 * Ray tracing's world (rt.metal): the client's loaded blocks in a window of W x H x W around the camera (all of the level's
 * height), toroidal as RecordWorld's (it moves by whole chunk columns as the camera crosses them; only the columns it gains are
 * written; block and light changes come from the game's own section updates).
 *
 * <p>A ushort per block: a solid one (it stops light: opaque blocks, leaves, lava; not glass, plants or water) is 0x8000 | its
 * palette number; anything else holds the light of its cell (sky << 4 | block) and its medium (bits 8..11: 1 water). Per 4 x 4 x 4
 * brick a 64-bit mask of its solid blocks (bit x + 4 z + 16 y) for the trace to skip empty space, and per palette number a
 * colour and light level (path tracing). For hardware ray tracing each written section's brick occupancy is handed to mcrt.m,
 * which keeps an acceleration structure of the bricks.
 */
final class RtWorld {
	static final int PALETTE_MAX = 32768;  // (15 bits; 4,096 ran out in heavy modpacks: further blocks cast no shadow, BH7 suspicion 7)
	final int w, h, minY;
	final long vox, bricks, sections, palette;
	private final long voxAddr, bricksAddr, sectionsAddr, paletteAddr;
	/** Per section slot: one above its top (0: nothing solid), for the window's top; the sections buffer: whether it holds anything. */
	private final short[] sectionTops;
	/** One above the highest solid cell in the window (window y), from the columns' tops. */
	int top;
	private final ClientLevel level;
	private final boolean skyLight;
	/** Window origin (world block coordinates of window x = 0, z = 0), chunk-aligned. */
	int ox, oz;
	private boolean placed;
	private final long[] columns;
	private final ArrayDeque<long[]> pending = new ArrayDeque<>();
	private final ArrayDeque<int[]> dirty = new ArrayDeque<>();
	private final java.util.Set<Long> dirtySet = new java.util.HashSet<>();
	/** Global block state id -> palette number with flag bit 15 (solid); 0 unassigned; -1 not solid and no colour (air-like). */
	private int[] stateToId = new int[32768];
	private int nextId = 1;
	private final long[] brickScratch = new long[64];
	private final long ctx;
	/** mcrt.m's state for hardware ray tracing (its sections' structures), 0 without. */
	long rt;
	boolean hw;
	long sectionsWritten;

	RtWorld(long ctx, ClientLevel level, int w) {
		this.ctx = ctx;
		this.level = level;
		this.w = w;
		this.minY = level.getMinY();
		this.h = level.getHeight();
		this.vox = RtNative.buffer(ctx, (long) w * w * this.h * 2);
		this.bricks = RtNative.buffer(ctx, (long) (w >> 2) * (w >> 2) * (this.h >> 2) * 8);
		this.palette = RtNative.buffer(ctx, (long) PALETTE_MAX * 8);
		this.sections = RtNative.buffer(ctx, (long) (w >> 4) * (w >> 4) * (this.h >> 4));
		this.sectionsAddr = RtNative.contents(this.sections);
		this.sectionTops = new short[(w >> 4) * (w >> 4) * (this.h >> 4)];
		this.voxAddr = RtNative.contents(this.vox);
		this.bricksAddr = RtNative.contents(this.bricks);
		this.paletteAddr = RtNative.contents(this.palette);
		this.skyLight = level.dimensionType().hasSkyLight();
		// (unwritten cells: air in open sky, or dark without one)
		short air = (short) (this.skyLight ? 0xF0 : 0);
		for (long i = 0, n = (long) w * w * this.h; i < n; i++) MemoryUtil.memPutShort(this.voxAddr + i * 2, air);
		this.columns = new long[(w >> 4) * (w >> 4)];
		java.util.Arrays.fill(this.columns, Long.MIN_VALUE);
	}

	void release() {
		for (long b : this.lightGrid) if (b != 0) RtNative.release(b);
		RtNative.release(this.vox);
		RtNative.release(this.bricks);
		RtNative.release(this.palette);
		RtNative.release(this.sections);
	}

	/** Bytes of GPU memory it holds (the voxels, the bricks, the palette). */
	long bytes() {
		return (long) this.w * this.w * this.h * 2 + (long) (this.w >> 2) * (this.w >> 2) * (this.h >> 2) * 8 + PALETTE_MAX * 8L;
	}

	ClientLevel level() {
		return this.level;
	}

	// ---- the window ----

	/** Keeps the window centred on the camera (block x, z): the columns it gains are queued, nearest first. */
	void follow(double camX, double camZ) {
		int half = this.w >> 1;
		int nx = ((int) Math.floor(camX) - half + 8) & ~15, nz = ((int) Math.floor(camZ) - half + 8) & ~15;
		if (this.placed && Math.abs(nx - this.ox) < 16 && Math.abs(nz - this.oz) < 16) return;
		this.ox = nx;
		this.oz = nz;
		this.placed = true;
		int cols = this.w >> 4;
		int cx0 = nx >> 4, cz0 = nz >> 4;
		java.util.List<long[]> want = new java.util.ArrayList<>();
		for (int dz = 0; dz < cols; dz++)
			for (int dx = 0; dx < cols; dx++) {
				int cx = cx0 + dx, cz = cz0 + dz;
				int slot = Math.floorMod(cz, cols) * cols + Math.floorMod(cx, cols);
				if (this.columns[slot] != chunkKey(cx, cz)) want.add(new long[] {cx, cz});
			}
		double ccx = camX / 16, ccz = camZ / 16;
		want.sort(java.util.Comparator.comparingDouble(c -> (c[0] + 0.5 - ccx) * (c[0] + 0.5 - ccx) + (c[1] + 0.5 - ccz) * (c[1] + 0.5 - ccz)));
		this.pending.clear();
		this.pending.addAll(want);
	}

	private static long chunkKey(int cx, int cz) {
		return (long) cx << 32 | (cz & 0xFFFFFFFFL);
	}

	void sectionDirty(int sx, int sy, int sz) {
		if (!this.inWindow(sx << 4, sz << 4)) return;
		if (this.dirtySet.add(SectionPos.asLong(sx, sy, sz))) this.dirty.add(new int[] {sx, sy, sz});
	}

	/** Writes queued columns and changed sections until budgetNanos is spent. Whether work is left. */
	boolean work(long budgetNanos) {
		long end = System.nanoTime() + budgetNanos;
		while (!this.dirty.isEmpty() && System.nanoTime() < end) {
			int[] s = this.dirty.poll();
			this.dirtySet.remove(SectionPos.asLong(s[0], s[1], s[2]));
			if (!this.inWindow(s[0] << 4, s[2] << 4)) continue;
			LevelChunk c = this.level.getChunkSource().getChunkNow(s[0], s[2]);
			if (c != null) this.writeSection(c, s[0], s[1], s[2]);
		}
		int retries = this.pending.size();
		while (!this.pending.isEmpty() && System.nanoTime() < end && retries-- > 0) {
			long[] col = this.pending.poll();
			int cx = (int) col[0], cz = (int) col[1];
			if (!this.inWindow(cx << 4, cz << 4)) continue;
			LevelChunk c = this.level.getChunkSource().getChunkNow(cx, cz);
			if (c == null) {
				this.pending.add(col);
				continue;
			}
			int cols = this.w >> 4;
			this.columns[Math.floorMod(cz, cols) * cols + Math.floorMod(cx, cols)] = chunkKey(cx, cz);
			int sections = this.h >> 4, minSection = this.minY >> 4;
			for (int i = sections - 1; i >= 0; i--) this.sectionDirty(cx, minSection + i, cz);
		}
		return !this.pending.isEmpty() || !this.dirty.isEmpty();
	}

	/** Columns and sections still to write (the window filling in). */
	int backlog() {
		return this.pending.size() + this.dirty.size();
	}

	private boolean inWindow(int x, int z) {
		return x >= this.ox && z >= this.oz && x < this.ox + this.w && z < this.oz + this.w;
	}

	private void writeSection(LevelChunk c, int sx, int sy, int sz) {
		int si = sy - (this.minY >> 4);
		if (si < 0 || si >= (this.h >> 4)) return;
		LevelChunkSection sec = c.getSections()[c.getSectionIndexFromSectionY(sy)];
		boolean empty = sec == null || sec.hasOnlyAir();
		int m = this.w - 1;
		var pos = SectionPos.of(sx, sy, sz);
		var engine = this.level.getLightEngine();
		DataLayer skyData = this.skyLight ? engine.getLayerListener(LightLayer.SKY).getDataLayerData(pos) : null;
		DataLayer blockData = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
		int skyFill = this.skyLight ? 15 : 0;
		long[] bm = this.brickScratch;
		java.util.Arrays.fill(bm, 0L);
		int nl = 0;
		this.lastEmission = 0;
		for (int y = 0; y < 16; y++) {
			int iy = (si << 4) + y;
			for (int z = 0; z < 16; z++) {
				int iz = ((sz << 4) + z) & m;
				for (int x = 0; x < 16; x++) {
					int ix = ((sx << 4) + x) & m;
					int id = empty ? -1 : this.id(sec.getBlockState(x, y, z));
					if (this.lastEmission > 0 && !empty) {
						// (a block that gives light: Torch Shadows' lights)
						if (nl + 4 > this.lightScratch.length) this.lightScratch = java.util.Arrays.copyOf(this.lightScratch, this.lightScratch.length * 2);
						this.lightScratch[nl++] = (sx << 4) + x;
						this.lightScratch[nl++] = (sy << 4) + y;
						this.lightScratch[nl++] = (sz << 4) + z;
						this.lightScratch[nl++] = this.lastEmission;
					}
					int v;
					if (id > 0 && (id & 0x8000) != 0) {
						v = id;
						bm[(x >> 2) | (y >> 2) << 2 | (z >> 2) << 4] |= 1L << ((x & 3) | (z & 3) << 2 | (y & 3) << 4);
					} else {
						int sl = skyData != null ? skyData.get(x, y, z) : skyFill, bl = blockData != null ? blockData.get(x, y, z) : 0;
						v = sl << 4 | bl | (id > 0 ? (id & 0xF00) : 0);
					}
					MemoryUtil.memPutShort(this.voxAddr + (((long) iy * this.w + iz) * this.w + ix) * 2, (short) v);
				}
			}
		}
		int bw = this.w >> 2;
		long sectionMask = 0;
		for (int k = 0; k < 64; k++) {
			int bx = (((sx << 4) & m) >> 2) + (k & 3), by = (si << 2) + ((k >> 2) & 3), bz = (((sz << 4) & m) >> 2) + ((k >> 4) & 3);
			MemoryUtil.memPutLong(this.bricksAddr + (((long) by * bw + bz) * bw + bx) * 8, bm[k]);
			if (bm[k] != 0) sectionMask |= 1L << k;
		}
		int s = this.w >> 4, slot = (si * s + (sz & (s - 1))) * s + (sx & (s - 1));
		MemoryUtil.memPutByte(this.sectionsAddr + slot, (byte) (sectionMask != 0 ? 1 : 0));
		// (the window's top: the highest section with anything in it, in whole sections: a cheap upper bound)
		this.sectionTops[slot] = (short) (sectionMask != 0 ? (si + 1) << 4 : 0);
		if (sectionMask != 0) this.top = Math.max(this.top, (si + 1) << 4);
		else if (this.top == (si + 1) << 4) {
			int t = 0;
			for (short v : this.sectionTops) t = Math.max(t, v);
			this.top = t;
		}
		if (this.hw && this.rt != 0) RtNative.section(this.rt, slot, sectionMask);
		this.sectionsWritten++;
		long key = SectionPos.asLong(sx, sy, sz);
		int[] had = nl > 0 ? this.sectionLights.put(key, java.util.Arrays.copyOf(this.lightScratch, nl)) : this.sectionLights.remove(key);
		if (nl > 0 || had != null) this.lightsDirty = true;
	}

	// ---- Torch Shadows: the blocks that give light, as a grid the GPU reads (rt.metal rt_torch) ----

	/** Per section (SectionPos key): its blocks that give light, world x, y, z and level, four ints each. */
	private final java.util.HashMap<Long, int[]> sectionLights = new java.util.HashMap<>();
	private int[] lightScratch = new int[256];
	/** Per global block state id: its light level + 1 (0: not known yet); the last id() call's level. */
	private byte[] emission = new byte[32768];
	private int lastEmission;
	/** The grid: a cell per 8 x 8 x 8 blocks of the window, 16 ints (a count, then up to 15 lights within reach). Three buffers in
	 * turn: one is rebuilt while the GPU may still read the others. */
	private final long[] lightGrid = new long[3];
	private int gridAt, gridOx = Integer.MIN_VALUE, gridOz;
	private int[] gridScratch;
	private boolean lightsDirty = true;
	private long lightsBuiltAt;
	/** Lights in the window at the last build. */
	int lightCount;

	/** The light grid for this frame (rebuilt when lights changed, at most 4 times a second, or the window moved). */
	long lights() {
		int gx = this.w >> 3, gy = this.h >> 3, cells = gx * gy * gx;
		if (this.lightGrid[0] == 0)
			for (int i = 0; i < 3; i++) this.lightGrid[i] = RtNative.buffer(this.ctx, (long) cells * 64);
		boolean moved = this.ox != this.gridOx || this.oz != this.gridOz;
		long now = System.nanoTime();
		if (moved || this.lightsDirty && now - this.lightsBuiltAt > 250_000_000L) {
			this.gridAt = (this.gridAt + 1) % 3;
			this.buildGrid(gx, gy, cells, RtNative.contents(this.lightGrid[this.gridAt]));
			this.gridOx = this.ox;
			this.gridOz = this.oz;
			this.lightsDirty = false;
			this.lightsBuiltAt = now;
		}
		return this.lightGrid[this.gridAt];
	}

	private void buildGrid(int gx, int gy, int cells, long addr) {
		if (this.gridScratch == null || this.gridScratch.length != cells * 16) this.gridScratch = new int[cells * 16];
		int[] g = this.gridScratch;
		java.util.Arrays.fill(g, 0);
		int count = 0;
		var it = this.sectionLights.entrySet().iterator();
		while (it.hasNext()) {
			var en = it.next();
			long key = en.getKey();
			if (!this.inWindow(SectionPos.x(key) << 4, SectionPos.z(key) << 4)) {
				it.remove();  // (the window moved off it: written again if it comes back)
				continue;
			}
			int[] l = en.getValue();
			for (int i = 0; i < l.length; i += 4) {
				int wx = l[i] - this.ox, wy = l[i + 1] - this.minY, wz = l[i + 2] - this.oz, e = l[i + 3];
				if (wx < 0 || wz < 0 || wy < 0 || wx >= this.w || wz >= this.w || wy >= this.h) continue;
				count++;
				int packed = wx | wz << 8 | wy << 16 | e << 28;
				float lx = wx + 0.5f, ly = wy + 0.5f, lz = wz + 0.5f;
				for (int cy = Math.max(0, (wy - e) >> 3); cy <= Math.min(gy - 1, (wy + e) >> 3); cy++)
					for (int cz = Math.max(0, (wz - e) >> 3); cz <= Math.min(gx - 1, (wz + e) >> 3); cz++)
						for (int cx = Math.max(0, (wx - e) >> 3); cx <= Math.min(gx - 1, (wx + e) >> 3); cx++) {
							// (within reach of the cell's box at all)
							float dx = Math.max(Math.max(cx * 8 - lx, lx - (cx * 8 + 8)), 0), dy = Math.max(Math.max(cy * 8 - ly, ly - (cy * 8 + 8)), 0),
								dz = Math.max(Math.max(cz * 8 - lz, lz - (cz * 8 + 8)), 0);
							if (dx * dx + dy * dy + dz * dz >= e * e) continue;
							int base = ((cy * gx + cz) * gx + cx) * 16, n = g[base];
							if (n < 15) {
								g[base + 1 + n] = packed;
								g[base] = n + 1;
								continue;
							}
							// (a crowded cell: the 15 that give it the most, by level less the distance to its middle)
							float score = e - dist(lx, ly, lz, cx, cy, cz);
							int weakest = -1;
							float least = score;
							for (int k = 0; k < 15; k++) {
								int o = g[base + 1 + k];
								float sc = (o >>> 28) - dist((o & 0xFF) + 0.5f, ((o >> 16) & 0xFFF) + 0.5f, ((o >> 8) & 0xFF) + 0.5f, cx, cy, cz);
								if (sc < least) {
									least = sc;
									weakest = k;
								}
							}
							if (weakest >= 0) g[base + 1 + weakest] = packed;
						}
			}
		}
		this.lightCount = count;
		MemoryUtil.memIntBuffer(addr, g.length).put(g);
	}

	private static float dist(float x, float y, float z, int cx, int cy, int cz) {
		float dx = x - (cx * 8 + 4), dy = y - (cy * 8 + 4), dz = z - (cz * 8 + 4);
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// ---- the palette ----

	/** A block state's palette word: 0x8000 | number for a solid one; for others their medium in bits 8..11, or -1 (none). */
	private int id(BlockState s) {
		int g = Block.getId(s);
		if (g >= this.stateToId.length) this.stateToId = java.util.Arrays.copyOf(this.stateToId, Math.max(g + 1, this.stateToId.length * 2));
		if (g >= this.emission.length) this.emission = java.util.Arrays.copyOf(this.emission, Math.max(g + 1, this.emission.length * 2));
		int em = this.emission[g];
		if (em == 0) this.emission[g] = (byte) (em = Math.min(15, s.getLightEmission()) + 1);
		this.lastEmission = em - 1;
		int id = this.stateToId[g];
		if (id != 0) return id;
		boolean liquid = s.getBlock() instanceof LiquidBlock;
		boolean lava = liquid && s.getFluidState().is(FluidTags.LAVA);
		boolean solid = !s.isAir() && (lava || !liquid && !s.propagatesSkylightDown() && s.getRenderShape() != RenderShape.INVISIBLE);
		if (!solid) {
			id = liquid || !s.getFluidState().isEmpty() ? 0x100 : -1;  // (water, and waterlogged blocks)
		} else if (this.nextId >= PALETTE_MAX) {
			id = -1;
		} else {
			int n = this.nextId++;
			this.writeEntry(n, s, lava);
			id = 0x8000 | n;
		}
		this.stateToId[g] = id;
		return id;
	}

	/** A palette entry: the block's colour (its texture's average, top face, tinted) and its light level. */
	private void writeEntry(int n, BlockState s, boolean lava) {
		int rgb;
		try {
			LodColors.Look l = LodColors.look(s);
			var biome = this.level.getBiome(BlockPos.containing(this.ox + (this.w >> 1), 64, this.oz + (this.w >> 1))).value();
			int tint = l.topTint() == LodColors.TINT_NONE ? 0xFFFFFF
				: LodColors.tint(0xFFFFFF, l.topTint(), l.constant(), biome, this.ox + (this.w >> 1), this.oz + (this.w >> 1));
			int a = l.top();
			rgb = ((a >> 16) & 255) * ((tint >> 16) & 255) / 255 << 16 | ((a >> 8) & 255) * ((tint >> 8) & 255) / 255 << 8 | (a & 255) * (tint & 255) / 255;
		} catch (RuntimeException e) {
			rgb = 0x808080;
		}
		if (lava) rgb = 0xD96415;
		MemoryUtil.memPutInt(this.paletteAddr + n * 8L, rgb & 0xFFFFFF | Math.min(15, s.getLightEmission()) << 24);
		MemoryUtil.memPutInt(this.paletteAddr + n * 8L + 4, 0);
	}
}
