package dev.cadence.shaders.cadence.render;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

/**
 * Cadence: which sections a ray of the shadow light can reach through open space, at section granularity, conservatively.
 *
 * <p>A straight ray travelling along d moves monotonically on each axis, so it can only leave a section through a face whose
 * outward direction has d·n > 0, and it can only cross a section between two faces that the section's visibility set connects
 * (vanilla's VisGraph: a path of non-solid-render blocks; the ray's own path through air, water, glass or leaves is one). A
 * breadth-first search from the faces where light enters the loaded area (the top, and the sides it shines into), through
 * permitted face pairs, reaches every section a ray can be in. A face of a block in a section next to a reached one can be lit
 * too, so the reached set is grown by one step along every permitted direction ("needed"). A section that is not needed holds
 * no surface any shadow-light ray reaches first, so its geometry never decides a texel of the shadow map.
 *
 * <p>Unknown is open: an uncompiled section (vanilla's UNCOMPILED answers "not visible") counts as fully open; so does an EMPTY
 * mesh unless its chunk section holds only solid-render blocks (EMPTY is also what an all-stone section compiles to). Only the
 * signs of d matter (with a small tolerance, so a component near zero allows both directions): the result holds for every
 * light direction in the same octant.
 */
public final class LightReach {
	private static final float EPS = 0.02f;

	private LightReach() {
	}

	/** needed[] indexed by index(area, sx, sy, sz); dx, dy, dz: the direction light travels. Render thread. */
	public static boolean[] needed(ViewArea area, Level level, float dx, float dy, float dz) {
		SectionPos c = area.getCameraSectionPos();
		int r = area.getViewDistance(), y0 = area.minSectionY(), y1 = area.maxSectionY();
		int w = 2 * r + 1, h = y1 - y0 + 1;
		byte[] entered = new byte[w * h * w];
		int[] queue = new int[w * h * w * 6];
		int head = 0, tail = 0;
		boolean[] exits = new boolean[6];
		for (Direction x : Direction.values()) exits[x.ordinal()] = x.getStepX() * dx + x.getStepY() * dy + x.getStepZ() * dz > -EPS;
		// sources: the boundary faces light enters through
		for (Direction x : Direction.values()) {
			if (!exits[x.ordinal()]) continue;
			Direction entry = x.getOpposite();
			for (int i = 0; i < w; i++) {
				for (int j = 0; j < (x.getAxis() == Direction.Axis.Y ? w : h); j++) {
					int sx, sy, sz;
					switch (x) {
						case DOWN -> { sx = i; sy = h - 1; sz = j; }
						case UP -> { sx = i; sy = 0; sz = j; }
						case EAST -> { sx = 0; sy = j; sz = i; }
						case WEST -> { sx = w - 1; sy = j; sz = i; }
						case SOUTH -> { sx = i; sy = j; sz = 0; }
						default -> { sx = i; sy = j; sz = w - 1; } // NORTH
					}
					int idx = (sx * h + sy) * w + sz;
					int bit = 1 << entry.ordinal();
					if ((entered[idx] & bit) == 0) {
						entered[idx] |= (byte) bit;
						queue[tail++] = idx * 6 + entry.ordinal();
					}
				}
			}
		}
		Direction[] dirs = Direction.values();
		while (head < tail) {
			int item = queue[head++], idx = item / 6;
			Direction entry = dirs[item % 6];
			int sx = idx / (h * w), sy = (idx / w) % h, sz = idx % w;
			SectionRenderDispatcher.RenderSection section = area.getRenderSectionAt(new BlockPos(
				SectionPos.sectionToBlockCoord(c.x() - r + sx), SectionPos.sectionToBlockCoord(y0 + sy), SectionPos.sectionToBlockCoord(c.z() - r + sz)));
			for (Direction exit : dirs) {
				if (!exits[exit.ordinal()] || exit == entry) continue;
				if (!open(section, level, entry, exit)) continue;
				int nx = sx + exit.getStepX(), ny = sy + exit.getStepY(), nz = sz + exit.getStepZ();
				if (nx < 0 || ny < 0 || nz < 0 || nx >= w || ny >= h || nz >= w) continue;
				int n = (nx * h + ny) * w + nz;
				Direction nEntry = exit.getOpposite();
				int bit = 1 << nEntry.ordinal();
				if ((entered[n] & bit) != 0) continue;
				entered[n] |= (byte) bit;
				queue[tail++] = n * 6 + nEntry.ordinal();
			}
		}
		boolean[] needed = new boolean[entered.length];
		for (int idx = 0; idx < entered.length; idx++) {
			if (entered[idx] == 0) continue;
			needed[idx] = true;
			int sx = idx / (h * w), sy = (idx / w) % h, sz = idx % w;
			for (Direction exit : dirs) {
				if (!exits[exit.ordinal()]) continue;
				int nx = sx + exit.getStepX(), ny = sy + exit.getStepY(), nz = sz + exit.getStepZ();
				if (nx < 0 || ny < 0 || nz < 0 || nx >= w || ny >= h || nz >= w) continue;
				needed[(nx * h + ny) * w + nz] = true;
			}
		}
		return needed;
	}

	public static boolean isNeeded(boolean[] needed, ViewArea area, BlockPos origin) {
		SectionPos c = area.getCameraSectionPos();
		int r = area.getViewDistance(), y0 = area.minSectionY(), y1 = area.maxSectionY();
		int w = 2 * r + 1, h = y1 - y0 + 1;
		int sx = SectionPos.blockToSectionCoord(origin.getX()) - (c.x() - r), sy = SectionPos.blockToSectionCoord(origin.getY()) - y0,
			sz = SectionPos.blockToSectionCoord(origin.getZ()) - (c.z() - r);
		if (sx < 0 || sy < 0 || sz < 0 || sx >= w || sy >= h || sz >= w) return true;
		return needed[(sx * h + sy) * w + sz];
	}

	private static boolean open(SectionRenderDispatcher.RenderSection section, Level level, Direction from, Direction to) {
		if (section == null) return true;
		var mesh = section.getSectionMesh();
		if (mesh == null || mesh == CompiledSectionMesh.UNCOMPILED) return true;
		if (mesh == CompiledSectionMesh.EMPTY) return !allSolid(section, level);
		return mesh.facesCanSeeEachother(from, to);
	}

	/** The section's chunk data holds nothing but solid-render blocks (palette check: may say "no" for a stale entry; never "yes" wrongly). */
	private static boolean allSolid(SectionRenderDispatcher.RenderSection section, Level level) {
		BlockPos o = section.getRenderOrigin();
		var chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(o.getX()), SectionPos.blockToSectionCoord(o.getZ()));
		if (chunk == null) return false;
		int index = level.getSectionIndex(o.getY());
		if (index < 0 || index >= chunk.getSections().length) return false;
		var data = chunk.getSections()[index];
		if (data.hasOnlyAir()) return false;
		return !data.getStates().maybeHas(state -> !state.isSolidRender());
	}
}
