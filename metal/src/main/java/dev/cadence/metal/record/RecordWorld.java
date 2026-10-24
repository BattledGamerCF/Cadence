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
 * height), as palette numbers (ushort, 0 = air) in a shared buffer the trace reads, x fastest, then z, then y, with an
 * occupancy bit per voxel. The window is toroidal: as the player crosses chunks it moves by whole chunk
 * columns, and only the columns it gains are written. Block changes come from the game's own section updates. Beside each voxel
 * its light (sky << 4 | block, from the game's own light engine; light changes mark sections for rebuilding too), for vanilla's
 * flat lighting.
 */
final class RecordWorld {
	static final int PALETTE_MAX = 4096, PALETTE_STRIDE = 16;  // bytes per entry (record.metal: a uint4)
	final int wlog, w, h, minY;
	final long grid, palette, occ, light, cells;
	private final long gridAddr, paletteAddr, occAddr, lightAddr, cellsAddr;
	/**
	 * The height field the trace skips air with: per column and section the highest block in it (y + 1 within the section, 0
	 * none), per column the highest block (window y + 1), and per 8 x 8-column cell the highest of its columns (`cells`, a uint
	 * each, read by record.metal: a ray above its cell's top crosses the cell in one step).
	 */
	private final byte[] sectionTop;
	private final short[] columnTop;
	private final int sections;
	private final boolean skyLight;
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
		this.palette = RecordNative.buffer(ctx, (long) PALETTE_MAX * PALETTE_STRIDE);
		this.bw = this.w >> 3;
		this.bh = this.h >> 3;
		this.gridAddr = RecordNative.contents(this.grid);
		this.paletteAddr = RecordNative.contents(this.palette);
		this.occ = RecordNative.buffer(ctx, (long) this.bw * this.bw * this.bh * 64);
		this.occAddr = RecordNative.contents(this.occ);
		MemoryUtil.memSet(this.occAddr, 0, (long) this.bw * this.bw * this.bh * 64);
		MemoryUtil.memSet(this.gridAddr, 0, (long) this.w * this.w * this.h * 2);
		this.light = RecordNative.buffer(ctx, (long) this.w * this.w * this.h);
		this.sections = this.h >> 4;
		this.sectionTop = new byte[this.w * this.w * this.sections];
		this.columnTop = new short[this.w * this.w];
		this.cells = RecordNative.buffer(ctx, (long) (this.w >> 3) * (this.w >> 3) * 4);
		this.cellsAddr = RecordNative.contents(this.cells);
		MemoryUtil.memSet(this.cellsAddr, 0, (long) (this.w >> 3) * (this.w >> 3) * 4);
		this.lightAddr = RecordNative.contents(this.light);
		this.skyLight = level.dimensionType().hasSkyLight();
		MemoryUtil.memSet(this.lightAddr, this.skyLight ? 0xF0 : 0, (long) this.w * this.w * this.h);
		this.columns = new long[(this.w >> 4) * (this.w >> 4)];
		java.util.Arrays.fill(this.columns, Long.MIN_VALUE);
	}

	void release() {
		RecordNative.releaseObject(this.grid);
		RecordNative.releaseObject(this.palette);
		RecordNative.releaseObject(this.occ);
		RecordNative.releaseObject(this.light);
		RecordNative.releaseObject(this.cells);
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
		boolean any = false;
		byte[] colTop = this.scratchTop;
		java.util.Arrays.fill(colTop, (byte) 0);
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
					// (x fastest, then z, then y: record.metal cellIndex)
					long at = ((long) iy * this.w + iz) * this.w + ix;
					MemoryUtil.memPutShort(this.gridAddr + at * 2, (short) id);
					int sl = skyData != null ? skyData.get(x, y, z) : skyFill, bl = blockData != null ? blockData.get(x, y, z) : 0;
					MemoryUtil.memPutByte(this.lightAddr + at, (byte) (sl << 4 | bl));
					long word = this.occAddr + (at >> 5) * 4;
					int bit = 1 << (at & 31);
					int old = MemoryUtil.memGetInt(word);
					MemoryUtil.memPutInt(word, id != 0 ? old | bit : old & ~bit);
					if (id != 0) {
						any = true;
						colTop[z * 16 + x] = (byte) (y + 1);
					}
				}
			}
		}
		if (any) this.top = Math.max(this.top, y0 + 16);
		// the height field: this section's tops, its columns' tops, their cells' tops
		for (int z = 0; z < 16; z++)
			for (int x = 0; x < 16; x++) {
				int col = (((sz << 4) + z) & m) * this.w + (((sx << 4) + x) & m);
				this.sectionTop[col * this.sections + si] = colTop[z * 16 + x];
				int t = 0;
				for (int k = this.sections - 1; k >= 0; k--) {
					int st = this.sectionTop[col * this.sections + k];
					if (st != 0) {
						t = (k << 4) + st;
						break;
					}
				}
				this.columnTop[col] = (short) t;
			}
		int cw = this.w >> 3;
		for (int k = 0; k < 4; k++) {
			int cx = ((((sx << 4) & m) >> 3) + (k & 1)), cz = ((((sz << 4) & m) >> 3) + (k >> 1));
			int t = 0;
			for (int z = 0; z < 8; z++)
				for (int x = 0; x < 8; x++) t = Math.max(t, this.columnTop[(cz * 8 + z) * this.w + cx * 8 + x]);
			MemoryUtil.memPutInt(this.cellsAddr + (cz * cw + cx) * 4L, t);
		}
	}

	private final byte[] scratchTop = new byte[256];

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

	/** A palette entry (record.metal): the top's and sides' colours (1x1 textures: average colour times tint), flags, shape box. */
	private void writeEntry(int id, BlockState s, boolean fluid) {
		long at = this.paletteAddr + (long) id * PALETTE_STRIDE;
		LodColors.Look l = LodColors.look(s);
		var biome = this.level.getBiome(BlockPos.containing(this.tintX, 64, this.tintZ)).value();
		int bx = (int) this.tintX, bz = (int) this.tintZ;
		int topTint = l.topTint() == LodColors.TINT_NONE ? 0xFFFFFF : LodColors.tint(0xFFFFFF, l.topTint(), l.constant(), biome, bx, bz);
		int sideTint = l.sideTint() == LodColors.TINT_NONE ? 0xFFFFFF : LodColors.tint(0xFFFFFF, l.sideTint(), l.constant(), biome, bx, bz);
		int top = times(l.top(), topTint), side = times(l.side(), sideTint);
		if (fluid) top = side = s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? 0xD96415 : 0x3F76E4;  // (their flat colours)
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
		MemoryUtil.memPutInt(at, top);
		MemoryUtil.memPutInt(at + 4, side);
		MemoryUtil.memPutInt(at + 8, flags);
		MemoryUtil.memPutInt(at + 12, box);
	}

	/** Two rgb colours multiplied, channel by channel. */
	private static int times(int a, int b) {
		int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255, g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255, bl = (a & 255) * (b & 255) / 255;
		return r << 16 | g << 8 | bl;
	}

	private static int q(double v) {
		return Math.clamp((int) Math.round(v * 16), 0, 16);
	}
}
