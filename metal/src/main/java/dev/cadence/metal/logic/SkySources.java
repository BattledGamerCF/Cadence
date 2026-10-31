package dev.cadence.metal.logic;

import dev.cadence.metal.mixin.logic.SkySourcesAccess;
import dev.cadence.metal.own.OwnPalette;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * Cadence Logic {@code skySources}: ChunkSkyLightSources.fillFrom (where sunlight stops in each of a chunk's 256 columns, run for
 * every chunk generated and every chunk a server sends) section by section instead of column by column. Vanilla walks each
 * column down from the highest non-empty section, one getBlockState and one edge test per block. Here each section's 4,096
 * palette ids are unpacked once and its few distinct states classified once: an edge whose lower block dampens light is
 * occluded, an edge between two blocks with no occlusion shape on the facing sides isn't (Shapes.faceShapeOccludes of two
 * empty shapes), and any other edge is vanilla's own test on the two states. Same walk, same answers (self-test step
 * "skysources" compares them on every loaded chunk). It was 3-4% of the render thread flying over new land, on a server too.
 */
public final class SkySources {
	private static final int CLASSIFIED = 256;
	private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
	/** The self-test's comparison: vanilla's own walk on this thread. */
	public static final ThreadLocal<Boolean> VANILLA = ThreadLocal.withInitial(() -> false);

	private static final class Scratch {
		final int[] ids = new int[4096], lowest = new int[256];
		final BlockState[] above = new BlockState[256], states = new BlockState[CLASSIFIED];
		final boolean[] done = new boolean[256], aboveDownEmpty = new boolean[256];
		final boolean[] dampens = new boolean[CLASSIFIED], upEmpty = new boolean[CLASSIFIED], downEmpty = new boolean[CLASSIFIED];
	}

	private SkySources() {
	}

	/** Set when this path can't run here at all (its palette access failed to start): vanilla's from then on. */
	private static volatile boolean broken;

	/**
	 * fillFrom's replacement; false leaves the call to vanilla. Anything unexpected (a palette id past its palette, from a
	 * damaged chunk) falls back to vanilla's walk, which then fails or answers as it always would: the map is written only at
	 * the end, so nothing is half done.
	 */
	public static boolean fill(SkySourcesAccess target, ChunkAccess chunk) {
		if (broken || VANILLA.get()) return false;
		try {
			return fill0(target, chunk);
		} catch (IllegalStateException e) {
			// (only OwnPalette's handles not fitting this game version turn it off for good; one odd section's own
			// failure, wrapped the same way, leaves just that chunk to vanilla, BH10-06)
			if (e.getCause() instanceof RuntimeException) return false;
			broken = true;
			System.out.println("[cadence] Cadence Logic skySources off: " + e);
			return false;
		} catch (LinkageError e) {
			broken = true;  // (OwnPalette's handles: not on this game version)
			System.out.println("[cadence] Cadence Logic skySources off: " + e);
			return false;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static boolean fill0(SkySourcesAccess target, ChunkAccess chunk) {
		int top = chunk.getHighestFilledSectionIndex();
		int minY = target.cadence$minY();
		if (top == -1) {
			target.cadence$fill(minY);
			return true;
		}
		Scratch s = SCRATCH.get();
		BlockState air = Blocks.AIR.defaultBlockState();
		boolean airDownEmpty = LightEngine.getOcclusionShape(air, Direction.DOWN).isEmpty();
		for (int c = 0; c < 256; c++) {
			s.done[c] = false;
			s.above[c] = air;
			s.aboveDownEmpty[c] = airDownEmpty;
			s.lowest[c] = minY;
		}
		int remaining = 256;
		for (int i = top; i >= 0 && remaining > 0; i--) {
			LevelChunkSection section = chunk.getSection(i);
			if (section.hasOnlyAir()) {
				for (int c = 0; c < 256; c++) {
					s.above[c] = air;
					s.aboveDownEmpty[c] = airDownEmpty;
				}
				continue;
			}
			int baseY = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
			Palette<BlockState> palette = OwnPalette.unpack(section.getStates(), s.ids);
			int n = palette.getSize();
			boolean classified = n <= CLASSIFIED;
			if (classified) {
				for (int id = 0; id < n; id++) {
					BlockState state = palette.valueFor(id);
					s.states[id] = state;
					s.dampens[id] = state.getLightDampening() != 0;
					s.upEmpty[id] = LightEngine.getOcclusionShape(state, Direction.UP).isEmpty();
					s.downEmpty[id] = LightEngine.getOcclusionShape(state, Direction.DOWN).isEmpty();
				}
			}
			for (int y = 15; y >= 0 && remaining > 0; y--) {
				int row = y << 8;
				for (int c = 0; c < 256; c++) {
					if (s.done[c]) continue;
					int id = s.ids[row | c];
					BlockState below;
					boolean occluded, belowDownEmpty;
					if (id >= n) throw new IndexOutOfBoundsException("palette id " + id + " of " + n);
					if (classified) {
						below = s.states[id];
						belowDownEmpty = s.downEmpty[id];
						if (s.dampens[id]) occluded = true;
						else if (s.aboveDownEmpty[c] && s.upEmpty[id]) occluded = false;
						else occluded = SkySourcesAccess.cadence$isEdgeOccluded(s.above[c], below);
					} else {
						below = palette.valueFor(id);
						belowDownEmpty = LightEngine.getOcclusionShape(below, Direction.DOWN).isEmpty();
						occluded = SkySourcesAccess.cadence$isEdgeOccluded(s.above[c], below);
					}
					if (occluded) {
						s.lowest[c] = baseY + y + 1;
						s.done[c] = true;
						remaining--;
					} else {
						s.above[c] = below;
						s.aboveDownEmpty[c] = belowDownEmpty;
					}
				}
			}
		}
		// (index(x, z) = x + 16 z, the section's own column order)
		for (int c = 0; c < 256; c++) target.cadence$set(c, Math.max(s.lowest[c], minY));
		for (int c = 0; c < 256; c++) s.above[c] = null;
		return true;
	}
}
