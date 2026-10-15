// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.chunk;

import java.util.Optional;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** D1 (poi=true): SectionStorage's own section lookup (loads the chunk's data if needed) and height range. */
@Mixin(SectionStorage.class)
public interface SectionStorageAccess {
	@Invoker("getOrLoad")
	Optional<?> cadence$getOrLoad(long sectionPos);

	@Accessor("levelHeightAccessor")
	LevelHeightAccessor cadence$levelHeightAccessor();
}
