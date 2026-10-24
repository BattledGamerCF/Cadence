package dev.cadence.metal.record;

import dev.cadence.metal.lod.LodColors;
import java.util.ArrayDeque;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.lwjgl.system.MemoryUtil;

/**
 * Record Mode's world: the client's loaded blocks in a window of W x H x W voxels around the camera (all of the level's
 * height), as palette numbers (ushort, 0 = air) in a shared buffer the trace reads, and per 8^3 brick whether it holds any
 * block (empty bricks are skipped whole). The window is toroidal: as the player crosses chunks it moves by whole chunk
 * columns, and only the columns it gains are written. Block changes come from the game's own section updates. Beside each voxel
 * its light (sky << 4 | block, from the game's own light engine; light changes mark sections for rebuilding too), for vanilla's
 * flat lighting.
 */
final class RecordWorld {
	static final int PALETTE_MAX = 4096, PALETTE_STRIDE = 64;  // bytes per entry (record.metal Pal)
	final int wlog, w, h, minY;
	final long grid, bricks, palette, occ, light;
	private final long gridAddr, brickAddr, paletteAddr, occAddr, lightAddr;
	private final boolean skyLight;
	/** Per brick whether it holds a block (the distance field in `bricks` is made from it); whether it changed since. */
	private final byte[] occupied;
	private boolean fieldDirty;
	private final int bw, bh;
	/** One above the highest block written (window-relative y): rays above it are sky. Only grows. */
	int top;
	/** Window origin (world block coordinates of window x = 0, z = 0), chunk-aligned. */
	int ox, oz;
	private boolean placed;
	/** Per window chunk column (x, z index): the chunk it holds, Long.MIN_VALUE none yet. */
	private final long[] columns;
	private final ArrayDeque<long[]> pending = new ArrayDeque<>();  // {cx, cz} columns to write
	private final ArrayDeque<int[]> dirty = new ArrayDeque<>();      // {sx, sy, sz} sections changed
	private final java.util.Set<Long> dirtySet = new java.util.HashSet<>();
	/** Global block state id -> palette number (0 unassigned, -1 air). */
	private int[] stateToId = new int[32768];
	private int nextId = 1;
	private final ClientLevel level;

	RecordWorld(long ctx, ClientLevel level, int wlog) {
		this.level = level;
		this.wlog = wlog;
		this.w = 1 << wlog;
		this.minY = level.getMinY();
		this.h = level.getHeight();
		this.grid = RecordNative.buffer(ctx, (long) this.w * this.w * this.h * 2);
		this.bricks = RecordNative.buffer(ctx, (long) (this.w >> 3) * (this.w >> 3) * (this.h >> 3));
		this.palette = RecordNative.buffer(ctx, (long) PALETTE_MAX * PALETTE_STRIDE);
		this.bw = this.w >> 3;
		this.bh = this.h >> 3;
		this.occupied = new byte[this.bw * this.bw * this.bh];
		this.gridAddr = RecordNative.contents(this.grid);
		this.brickAddr = RecordNative.contents(this.bricks);
		this.paletteAddr = RecordNative.contents(this.palette);
		MemoryUtil.memSet(this.brickAddr, 8, (long) this.bw * this.bw * this.bh);
		this.occ = RecordNative.buffer(ctx, (long) this.bw * this.bw * this.bh * 64);
		this.occAddr = RecordNative.contents(this.occ);
		MemoryUtil.memSet(this.occAddr, 0, (long) this.bw * this.bw * this.bh * 64);
		MemoryUtil.memSet(this.gridAddr, 0, (long) this.w * this.w * this.h * 2);
		this.light = RecordNative.buffer(ctx, (long) this.w * this.w * this.h);
		this.lightAddr = RecordNative.contents(this.light);
		this.skyLight = level.dimensionType().hasSkyLight();
		MemoryUtil.memSet(this.lightAddr, this.skyLight ? 0xF0 : 0, (long) this.w * this.w * this.h);
		this.columns = new long[(this.w >> 4) * (this.w >> 4)];
		java.util.Arrays.fill(this.columns, Long.MIN_VALUE);
	}

	void release() {
		RecordNative.releaseObject(this.grid);
		RecordNative.releaseObject(this.bricks);
		RecordNative.releaseObject(this.palette);
		RecordNative.releaseObject(this.occ);
		RecordNative.releaseObject(this.light);
	}

	// ---- the window ----

	/** Keeps the window centred on the camera (block x, z): columns it gains are queued for writing. */
	void follow(double camX, double camZ) {
		int half = this.w >> 1;
		int nx = ((int) Math.floor(camX) - half) & ~15, nz = ((int) Math.floor(camZ) - half) & ~15;
		if (this.placed && Math.abs(nx - this.ox) < 16 && Math.abs(nz - this.oz) < 16) return;
		this.ox = nx;
		this.oz = nz;
		this.placed = true;
		int cols = this.w >> 4;
		int cx0 = nx >> 4, cz0 = nz >> 4;
		// nearest first: the camera's own column, then rings outward
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

	/** The game marked a section for rebuilding (a block changed): it's written again. */
	void sectionDirty(int sx, int sy, int sz) {
		if (this.dirtySet.add(chunkKey(sx, sz) * 31 + sy)) this.dirty.add(new int[] {sx, sy, sz});
	}

	/** Writes queued columns and changed sections, until `budgetNanos` is spent. Whether work is left. */
	boolean work(long budgetNanos) {
		long end = System.nanoTime() + budgetNanos;
		while (!this.dirty.isEmpty() && System.nanoTime() < end) {
			int[] s = this.dirty.poll();
			this.dirtySet.remove(chunkKey(s[0], s[2]) * 31 + s[1]);
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
				this.pending.add(col);  // not loaded yet: later
				continue;
			}
			this.writeColumn(c, cx, cz);
		}
		return !this.pending.isEmpty() || !this.dirty.isEmpty();
	}

	private boolean inWindow(int x, int z) {
		return x >= this.ox && z >= this.oz && x < this.ox + this.w && z < this.oz + this.w;
	}

	private void writeColumn(LevelChunk c, int cx, int cz) {
		int cols = this.w >> 4;
		this.columns[Math.floorMod(cz, cols) * cols + Math.floorMod(cx, cols)] = chunkKey(cx, cz);
		int sections = this.h >> 4, minSection = this.minY >> 4;
		for (int i = 0; i < sections; i++) this.writeSection(c, cx, minSection + i, cz);
	}

	private void writeSection(LevelChunk c, int sx, int sy, int sz) {
		int si = sy - (this.minY >> 4);
		if (si < 0 || si >= (this.h >> 4)) return;
		LevelChunkSection sec = c.getSections()[c.getSectionIndexFromSectionY(sy)];
		boolean empty = sec == null || sec.hasOnlyAir();
		int m = this.w - 1;
		int y0 = si << 4;
		int[] counts = new int[8];
		int bwv = this.w >> 3;
		// the section's light (null: no data kept there; open sky, or no block light)
		var pos = net.minecraft.core.SectionPos.of(sx, sy, sz);
		var engine = this.level.getLightEngine();
		DataLayer skyData = this.skyLight ? engine.getLayerListener(LightLayer.SKY).getDataLayerData(pos) : null;
		DataLayer blockData = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
		int skyFill = this.skyLight ? 15 : 0;
		for (int y = 0; y < 16; y++) {
			int iy = y0 + y;
			for (int z = 0; z < 16; z++) {
				int iz = ((sz << 4) + z) & m;
				for (int x = 0; x < 16; x++) {
					int ix = ((sx << 4) + x) & m;
					int id = empty ? 0 : this.id(sec.getBlockState(x, y, z));
					// (brick-major, as record.metal voxelAt reads it)
					long brick = ((long) (iy >> 3) * bwv + (iz >> 3)) * bwv + (ix >> 3);
					int local = ((iy & 7) * 8 + (iz & 7)) * 8 + (ix & 7);
					MemoryUtil.memPutShort(this.gridAddr + (brick * 512 + local) * 2, (short) id);
					int sl = skyData != null ? skyData.get(x, y, z) : skyFill, bl = blockData != null ? blockData.get(x, y, z) : 0;
					MemoryUtil.memPutByte(this.lightAddr + brick * 512 + local, (byte) (sl << 4 | bl));
					long word = this.occAddr + (brick * 16 + (local >> 5)) * 4;
					int bit = 1 << (local & 31);
					int old = MemoryUtil.memGetInt(word);
					MemoryUtil.memPutInt(word, id != 0 ? old | bit : old & ~bit);
					if (id != 0) counts[(y >> 3) << 2 | (z >> 3) << 1 | (x >> 3)]++;
				}
			}
		}
		int bm = this.bw - 1;
		boolean any = false;
		for (int k = 0; k < 8; k++) {
			int bx = (((sx << 4) >> 3) + (k & 1)) & bm, bz = (((sz << 4) >> 3) + ((k >> 1) & 1)) & bm, by = (y0 >> 3) + (k >> 2);
			byte o = (byte) (counts[k] > 0 ? 1 : 0);
			int at = (by * this.bw + bz) * this.bw + bx;
			if (this.occupied[at] != o) {
				this.occupied[at] = o;
				this.fieldDirty = true;
			}
			any |= counts[k] > 0;
		}
		if (any) this.top = Math.max(this.top, y0 + 16);
	}

	// ---- the brick distance field ----

	private byte[] dist, tmp;

	/**
	 * The bricks' distance field again if any brick filled or emptied: per brick 0 if it holds a block, else the Chebyshev
	 * distance in bricks to the nearest one that does (at most 8), so a ray crosses that much empty space in one step. Eight
	 * dilations, each a 3 x 3 x 3 minimum done as three 1-D passes (toroidal like the window; the window's edge is no
	 * barrier, which only ever makes a distance smaller).
	 */
	void updateField() {
		if (!this.fieldDirty) return;
		this.fieldDirty = false;
		int n = this.occupied.length;
		if (this.dist == null) {
			this.dist = new byte[n];
			this.tmp = new byte[n];
		}
		byte[] d = this.dist, t = this.tmp;
		for (int i = 0; i < n; i++) d[i] = (byte) (this.occupied[i] != 0 ? 0 : 8);
		int bw = this.bw, bh = this.bh, m = bw - 1;
		for (int it = 0; it < 8; it++) {
			// x
			for (int y = 0; y < bh; y++)
				for (int z = 0; z < bw; z++) {
					int row = (y * bw + z) * bw;
					for (int x = 0; x < bw; x++)
						t[row + x] = (byte) Math.min(d[row + x], Math.min(d[row + ((x + 1) & m)], d[row + ((x - 1) & m)]) + 1);
				}
			// z
			for (int y = 0; y < bh; y++)
				for (int z = 0; z < bw; z++)
					for (int x = 0; x < bw; x++) {
						int at = (y * bw + z) * bw + x;
						d[at] = (byte) Math.min(t[at], Math.min(t[(y * bw + ((z + 1) & m)) * bw + x], t[(y * bw + ((z - 1) & m)) * bw + x]) + 1);
					}
			// y (no wrap: the window has the level's whole height)
			for (int y = 0; y < bh; y++)
				for (int i = 0; i < bw * bw; i++) {
					int at = y * bw * bw + i;
					int up = y + 1 < bh ? d[at + bw * bw] : 8, down = y > 0 ? d[at - bw * bw] : 8;
					t[at] = (byte) Math.min(d[at], Math.min(up, down) + 1);
				}
			System.arraycopy(t, 0, d, 0, n);
		}
		for (int i = 0; i < n; i++) MemoryUtil.memPutByte(this.brickAddr + i, (byte) Math.min(8, d[i]));
	}

	// ---- the palette ----

	private int id(BlockState s) {
		int g = Block.getId(s);
		if (g >= this.stateToId.length) this.stateToId = java.util.Arrays.copyOf(this.stateToId, Math.max(g + 1, this.stateToId.length * 2));
		int id = this.stateToId[g];
		if (id > 0) return id;
		if (id < 0) return 0;
		boolean fluid = s.getBlock() instanceof LiquidBlock;
		if (s.isAir() || !fluid && s.getRenderShape() == RenderShape.INVISIBLE || this.nextId >= PALETTE_MAX) {
			this.stateToId[g] = -1;
			return 0;
		}
		id = this.nextId++;
		this.stateToId[g] = id;
		this.writeEntry(id, s, fluid);
		return id;
	}

	/** Tints are those of the biome at the camera when the block was first seen (an approximation: one tint per block). */
	private double tintX, tintZ;

	void tintAt(double x, double z) {
		this.tintX = x;
		this.tintZ = z;
	}

	private void writeEntry(int id, BlockState s, boolean fluid) {
		long at = this.paletteAddr + (long) id * PALETTE_STRIDE;
		LodColors.Look l = LodColors.look(s);
		var biome = this.level.getBiome(BlockPos.containing(this.tintX, 64, this.tintZ)).value();
		int bx = (int) this.tintX, bz = (int) this.tintZ;
		float[] top = fluid ? new float[4] : l.topUv(), side = fluid ? new float[4] : l.sideUv();
		for (int i = 0; i < 4; i++) {
			MemoryUtil.memPutFloat(at + i * 4L, top[i]);
			MemoryUtil.memPutFloat(at + 16 + i * 4L, side[i]);
		}
		int topTint = l.topTint() == LodColors.TINT_NONE ? 0xFFFFFF : LodColors.tint(0xFFFFFF, l.topTint(), l.constant(), biome, bx, bz);
		int sideTint = l.sideTint() == LodColors.TINT_NONE ? 0xFFFFFF : LodColors.tint(0xFFFFFF, l.sideTint(), l.constant(), biome, bx, bz);
		int topAvg = l.top(), sideAvg = l.side();
		if (fluid) {
			// water and lava: their flat colours (no sprite)
			topAvg = sideAvg = s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? 0xD96415 : 0x3F76E4;
			topTint = sideTint = 0xFFFFFF;
		}
		MemoryUtil.memPutInt(at + 32, topTint);
		MemoryUtil.memPutInt(at + 36, sideTint);
		MemoryUtil.memPutInt(at + 40, topAvg);
		MemoryUtil.memPutInt(at + 44, sideAvg);
		int flags = 0, box = 0;
		if (l.cross() && !fluid) flags |= 1;
		if (fluid) flags |= 2;
		if (!fluid && !l.cross()) {
			var shape = s.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			if (!shape.isEmpty()) {
				AABB b = shape.bounds();
				int x0 = q(b.minX), y0 = q(b.minY), z0 = q(b.minZ), x1 = q(b.maxX), y1 = q(b.maxY), z1 = q(b.maxZ);
				if (x0 > 0 || y0 > 0 || z0 > 0 || x1 < 16 || y1 < 16 || z1 < 16) {
					flags |= 4;
					box = x0 | y0 << 5 | z0 << 10 | x1 << 15 | y1 << 20 | z1 << 25;
				}
			}
		}
		MemoryUtil.memPutInt(at + 48, flags);
		MemoryUtil.memPutInt(at + 52, box);
	}

	private static int q(double v) {
		return Math.clamp((int) Math.round(v * 16), 0, 16);
	}
}
