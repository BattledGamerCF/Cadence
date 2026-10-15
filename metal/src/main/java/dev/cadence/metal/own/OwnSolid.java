// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.lwjgl.system.MemoryUtil;

/**
 * Solid-section occlusion's data (-Dcadence.own.solidOcc): which loaded sections are all opaque full cubes (vanilla's
 * isSolidRender), as a bit per section in a toroidal grid of chunk columns the GPU reads. A section is checked from its block
 * palette alone (no entry that isn't solid-render and no air: then every block is opaque), whenever vanilla marks it dirty
 * (block changes, chunk loads); unloaded chunks clear their column. Only these sections occlude, so water, glass, leaves
 * and anything else see-through never hide what is behind them.
 */
public final class OwnSolid {
	public static final boolean ON = OwnTerrain.FLAG && Boolean.getBoolean("cadence.own.solidOcc");
	/** Sections are tracked with the flag, or for the probe (-Dcadence.own.probeModes containing solidocc). */
	static final boolean FEED = ON || OwnTerrain.FLAG && System.getProperty("cadence.own.probeModes", "").contains("solidocc");
	static final int GRID = 128, COL_BYTES = 16;
	private static final ConcurrentLinkedQueue<long[]> DIRTY = new ConcurrentLinkedQueue<>();
	private static final ConcurrentLinkedQueue<long[]> UNLOADED = new ConcurrentLinkedQueue<>();

	final long buffer, address;
	private int checked, solid;
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	OwnSolid(long ctx) {
		this.buffer = OwnNative.buffer(ctx, (long) GRID * GRID * COL_BYTES, true);
		this.address = OwnNative.contents(this.buffer);
		for (int i = 0; i < GRID * GRID; i++) MemoryUtil.memPutInt(this.address + (long) i * COL_BYTES, Integer.MIN_VALUE);  // no column
	}

	/** Vanilla marked a section dirty (LevelExtractor.setSectionDirty): any thread. */
	public static void dirty(int sx, int sy, int sz) {
		if (FEED) DIRTY.add(new long[] {sx, sy, sz});
	}

	/** A chunk left the client (ClientLevel.unload). */
	public static void unloaded(int cx, int cz) {
		if (FEED) UNLOADED.add(new long[] {cx, cz});
	}

	/** Render thread, each frame before the cull: apply the queued checks. */
	void update() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;
		for (long[] u; (u = UNLOADED.poll()) != null; ) {
			long col = this.col((int) u[0], (int) u[1]);
			if (MemoryUtil.memGetInt(col) == (int) u[0] && MemoryUtil.memGetInt(col + 4) == (int) u[1]) {
				MemoryUtil.memPutInt(col + 8, 0);
				MemoryUtil.memPutInt(col + 12, 0);
			}
		}
		int minY = level.getMinSectionY(), levels = Math.min(64, level.getSectionsCount());
		this.seen.clear();
		for (long[] d; (d = DIRTY.poll()) != null; ) {
			int sx = (int) d[0], sy = (int) d[1], sz = (int) d[2], bit = sy - minY;
			if (!this.seen.add(net.minecraft.core.SectionPos.asLong(sx, sy, sz))) continue;  // (vanilla marks neighbours many times over)
			if (bit < 0 || bit >= levels) continue;
			LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
			boolean opaque = false;
			if (chunk != null) {
				LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
				opaque = !section.hasOnlyAir() && !section.maybeHas(s -> !s.isSolidRender());
			}
			this.checked++;
			if (opaque) this.solid++;
			long col = this.col(sx, sz);
			if (MemoryUtil.memGetInt(col) != sx || MemoryUtil.memGetInt(col + 4) != sz) {
				// another column held this cell: start this one empty (written mask first, then the coordinates that make it valid)
				MemoryUtil.memPutInt(col + 8, 0);
				MemoryUtil.memPutInt(col + 12, 0);
				MemoryUtil.memPutInt(col, sx);
				MemoryUtil.memPutInt(col + 4, sz);
			}
			long word = col + (bit < 32 ? 8 : 12);
			int m = MemoryUtil.memGetInt(word), b = 1 << (bit & 31);
			MemoryUtil.memPutInt(word, opaque ? m | b : m & ~b);
		}
	}

	private long col(int cx, int cz) {
		return this.address + (long) ((cx & (GRID - 1)) + (cz & (GRID - 1)) * GRID) * COL_BYTES;
	}

	String stats() {
		String r = "sections checked " + this.checked + ", opaque " + this.solid;
		this.checked = 0;
		this.solid = 0;
		return r;
	}
}
