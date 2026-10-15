package dev.cadence.shaders.cadence;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Cadence: a particle moving through open air skips vanilla's collision search.
 *
 * <p>Vanilla moves a physics particle with Entity.collideBoundingBox: BlockCollisions walks every cell of the swept box
 * grown by one cell each way, through a Guava iterator into an immutable list of shapes, and returns the movement unchanged
 * when the list is empty. Its rule per cell (Cursor3D type = how many of the cell's coordinates lie on the grown range's
 * boundary): type 0 (inside) counts any block, type 1 only blocks with a large collision shape (fences, walls), type 2
 * only a moving piston, type 3 (corners) nothing. Here the same cells are checked with the same rule first, and only for
 * "could this cell give a shape at all": inside cells must be air, type 1 cells must not have a large collision shape,
 * type 2 cells must not be a moving piston. When no cell can, vanilla's list would be empty and the answer is the movement
 * unchanged: exactly vanilla's. Otherwise (or a box over 6 cells wide, or an unloaded chunk) vanilla's search runs as
 * before. -Dcadence.particlePhysics=false: always vanilla.
 */
public final class ParticlePhysics {
	public static volatile boolean enabled = !"false".equals(System.getProperty("cadence.particlePhysics"));

	public static long skipped, searched;

	private ParticlePhysics() {
	}

	/** True when no cell vanilla's search visits for {@code swept} can give it a shape. */
	public static boolean noShapes(Level level, AABB swept) {
		int x0 = Mth.floor(swept.minX - 1.0E-7) - 1, x1 = Mth.floor(swept.maxX + 1.0E-7) + 1;
		int y0 = Mth.floor(swept.minY - 1.0E-7) - 1, y1 = Mth.floor(swept.maxY + 1.0E-7) + 1;
		int z0 = Mth.floor(swept.minZ - 1.0E-7) - 1, z1 = Mth.floor(swept.maxZ + 1.0E-7) + 1;
		if (x1 - x0 > 6 || y1 - y0 > 6 || z1 - z0 > 6) return false;
		int minSection = level.getMinSectionY(), maxSection = level.getMaxSectionY();
		for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
			for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
				if (!level.hasChunk(cx, cz)) return false; // vanilla decides (it skips missing chunks its own way)
				var chunk = level.getChunk(cx, cz);
				for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
					if (sy < minSection || sy > maxSection) continue; // outside the world: void air
					LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
					if (section.hasOnlyAir()) continue;
					int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15);
					int ay = Math.max(y0, sy << 4), by = Math.min(y1, (sy << 4) + 15);
					int az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
					for (int x = ax; x <= bx; x++)
						for (int y = ay; y <= by; y++)
							for (int z = az; z <= bz; z++) {
								int type = (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0) + (z == z0 || z == z1 ? 1 : 0);
								if (type == 3) continue;
								BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
								if (type == 0 ? !state.isAir() : type == 1 ? state.hasLargeCollisionShape() : state.is(Blocks.MOVING_PISTON))
									return false;
							}
				}
			}
		}
		return true;
	}
}
