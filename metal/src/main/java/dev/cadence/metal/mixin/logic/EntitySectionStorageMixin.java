package dev.cadence.metal.mixin.logic;

import dev.cadence.metal.logic.EntitySectionScan;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Cadence Logic {@code entitySections}: entity searches visit only the sections in the box (see EntitySectionScan). */
@Mixin(EntitySectionStorage.class)
abstract class EntitySectionStorageMixin<T extends EntityAccess> {
	@Shadow @Final private Long2ObjectMap<EntitySection<T>> sections;
	@Shadow @Final private LongSortedSet sectionIds;

	/**
	 * @author Cadence
	 * @reason the same sections in the same order, by direct lookup instead of a walk over each column's every section
	 */
	@Overwrite
	public void forEachAccessibleNonEmptySection(AABB box, AbortableIterationConsumer<EntitySection<T>> consumer) {
		int minX = SectionPos.posToSectionCoord(box.minX - 2.0);
		int minY = SectionPos.posToSectionCoord(box.minY - 4.0);
		int minZ = SectionPos.posToSectionCoord(box.minZ - 2.0);
		int maxX = SectionPos.posToSectionCoord(box.maxX + 2.0);
		int maxY = SectionPos.posToSectionCoord(box.maxY + 0.0);
		int maxZ = SectionPos.posToSectionCoord(box.maxZ + 2.0);
		if (EntitySectionScan.handles(minY, maxY, minZ, maxZ)) {
			EntitySectionScan.scan(this.sections, minX, maxX, minY, maxY, minZ, maxZ, consumer);
			return;
		}
		// vanilla's walk: every existing section of each column, filtered by Y and Z
		for (int x = minX; x <= maxX; x++) {
			long from = SectionPos.asLong(x, 0, 0);
			long to = SectionPos.asLong(x, -1, -1);
			LongIterator it = this.sectionIds.subSet(from, to + 1L).iterator();
			while (it.hasNext()) {
				long key = it.nextLong();
				int y = SectionPos.y(key);
				int z = SectionPos.z(key);
				if (y < minY || y > maxY || z < minZ || z > maxZ) continue;
				EntitySection<T> section = this.sections.get(key);
				if (section != null && !section.isEmpty() && section.getStatus().isAccessible()
					&& consumer.accept(section).shouldAbort()) return;
			}
		}
	}
}
