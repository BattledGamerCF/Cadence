package dev.cadence.metal.logic;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;

/**
 * Cadence Logic {@code entitySections}: the section visit behind every entity search by box (mobs pushing each other,
 * hoppers looking for items, item merging and pickup, AI sensors, entity collisions).
 *
 * <p>Vanilla's EntitySectionStorage walks, for each section column X the box touches, every existing entity section with
 * that X in the whole loaded world (a sorted-set view over all Z and Y), keeping those inside the box's Y and Z range. A
 * one-block box with 20 loaded chunks along Z walks hundreds of sections and allocates a set view and an iterator per
 * column. This visits only the keys inside the range, by direct lookup, in the same order: a column's keys sort as
 * unsigned bit patterns, Z (22 bits) above Y (20 bits), so non-negative coordinates come first, then negative ones, each
 * ascending. Same sections, same order, same checks; boxes too tall or wide for that to pay off take vanilla's walk.
 */
public final class EntitySectionScan {
	/** Above this many Z x Y keys per column, vanilla's walk of the column's existing sections is used. */
	static final long MAX_KEYS = 1024;
	private static final int Z_MIN = -(1 << 21), Z_MAX = (1 << 21) - 1, Y_MIN = -(1 << 19), Y_MAX = (1 << 19) - 1;

	private EntitySectionScan() {
	}

	/** Whether {@link #scan} handles this range (the key pattern holds and the walk is short). */
	public static boolean handles(int minY, int maxY, int minZ, int maxZ) {
		if (minY > maxY || minZ > maxZ) return true; // nothing in range: vanilla visits nothing either
		if (minY < Y_MIN || maxY > Y_MAX || minZ < Z_MIN || maxZ > Z_MAX) return false;
		return (long) (maxY - minY + 1) * (maxZ - minZ + 1) <= MAX_KEYS;
	}

	/** Vanilla's visit for section coordinates already computed from the box; only when {@link #handles} said so. */
	public static <T extends EntityAccess> void scan(Long2ObjectMap<EntitySection<T>> sections, int minX, int maxX, int minY, int maxY,
		int minZ, int maxZ, AbortableIterationConsumer<EntitySection<T>> consumer) {
		if (minY > maxY || minZ > maxZ) return;
		for (int x = minX; x <= maxX; x++) {
			// Z in key order: 0..maxZ, then minZ..-1
			for (int zPass = 0; zPass < 2; zPass++) {
				int zFrom = zPass == 0 ? Math.max(minZ, 0) : minZ;
				int zTo = zPass == 0 ? maxZ : Math.min(maxZ, -1);
				for (int z = zFrom; z <= zTo; z++) {
					for (int yPass = 0; yPass < 2; yPass++) {
						int yFrom = yPass == 0 ? Math.max(minY, 0) : minY;
						int yTo = yPass == 0 ? maxY : Math.min(maxY, -1);
						for (int y = yFrom; y <= yTo; y++) {
							EntitySection<T> section = sections.get(SectionPos.asLong(x, y, z));
							if (section != null && !section.isEmpty() && section.getStatus().isAccessible()
								&& consumer.accept(section).shouldAbort()) return;
						}
					}
				}
			}
		}
	}
}
