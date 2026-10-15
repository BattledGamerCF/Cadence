package dev.cadence.metal.mixin.logic;

import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.SecondaryPoiSensor;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Cadence Logic {@code villagerSensor}: every 40 ticks each villager's SecondaryPoiSensor reads the 405 blocks around it
 * (9 x 5 x 9, a new BlockPos each) looking for its profession's secondary work blocks (a farmer's farmland). Every
 * profession but the farmer has none, so nothing can match and vanilla ends by erasing the memory: that is done directly.
 * With work blocks, the same positions are read in the same order through one mutable position, and a match is stored as
 * an immutable copy, as vanilla stores it. The blocks around a ticking villager are always loaded, so skipping the reads
 * changes nothing else.
 */
@Mixin(SecondaryPoiSensor.class)
abstract class SecondaryPoiSensorMixin {
	/**
	 * @author Cadence
	 * @reason no block reads when the profession has no secondary work blocks; one mutable position otherwise
	 */
	@Overwrite
	protected void doTick(ServerLevel level, Villager body) {
		ResourceKey<Level> dimension = level.dimension();
		BlockPos center = body.blockPosition();
		ImmutableSet<Block> wanted = body.getVillagerData().profession().value().secondaryPoi();
		Brain<?> brain = body.getBrain();
		if (wanted.isEmpty()) {
			brain.eraseMemory(MemoryModuleType.SECONDARY_JOB_SITE);
			return;
		}
		List<GlobalPos> found = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -4; dx <= 4; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -4; dz <= 4; dz++) {
					pos.setWithOffset(center, dx, dy, dz);
					if (wanted.contains(level.getBlockState(pos).getBlock())) found.add(GlobalPos.of(dimension, pos.immutable()));
				}
			}
		}
		if (!found.isEmpty()) brain.setMemory(MemoryModuleType.SECONDARY_JOB_SITE, found);
		else brain.eraseMemory(MemoryModuleType.SECONDARY_JOB_SITE);
	}
}
