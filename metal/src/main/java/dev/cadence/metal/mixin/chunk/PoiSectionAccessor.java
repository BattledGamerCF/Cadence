// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.chunk;

import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** D1 (poi=true): the section's records by type, iterated in the same order as its getRecords stream. */
@Mixin(PoiSection.class)
public interface PoiSectionAccessor {
	@Accessor("byType")
	Map<Holder<PoiType>, Set<PoiRecord>> cadence$byType();
}
