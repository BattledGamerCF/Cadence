// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * D3 (-Dcadence.chunk.ticks=true): ServerLevel.tickChunk's random-tick loop (11.7% of the server thread on the Neo with 200 mobs)
 * without the per-pick work that doesn't change anything: the section's Y comes from one min-section offset per chunk instead of
 * a chain of height-accessor calls per section, and the BlockPos of a pick is only created when its block or fluid state
 * actually ticks. The same LCG steps (Level.randValue, as getBlockRandomPos), the same picks in the same order, the same states
 * read, the same tick calls with the same RandomSource, the same profiler sections.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelTickMixin {
	/**
	 * @author Cadence
	 * @reason same random ticks with less per-pick overhead (opt-in); see class doc.
	 */
	@Overwrite
	public void tickChunk(final LevelChunk chunk, final int tickSpeed) {
		ServerLevel self = (ServerLevel) (Object) this;
		LevelRandAccessor lcg = (LevelRandAccessor) self;
		ChunkPos chunkPos = chunk.getPos();
		int minX = chunkPos.getMinBlockX();
		int minZ = chunkPos.getMinBlockZ();
		ProfilerFiller profiler = Profiler.get();
		profiler.push("iceandsnow");

		for (int i = 0; i < tickSpeed; i++) {
			if (self.getRandom().nextInt(48) == 0) {
				self.tickPrecipitation(self.getBlockRandomPos(minX, 0, minZ, 15));
			}
		}

		profiler.popPush("tickBlocks");
		if (tickSpeed > 0) {
			LevelChunkSection[] sections = chunk.getSections();
			int minSectionY = chunk.getMinSectionY();
			for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
				LevelChunkSection section = sections[sectionIndex];
				if (section.isRandomlyTicking()) {
					int minYInSection = SectionPos.sectionToBlockCoord(sectionIndex + minSectionY);
					for (int i = 0; i < tickSpeed; i++) {
						int rand = lcg.cadence$randValue() * 3 + 1013904223;
						lcg.cadence$randValue(rand);
						int val = rand >> 2;
						int lx = val & 15, ly = val >> 16 & 15, lz = val >> 8 & 15;
						profiler.push("randomTick");
						BlockState blockState = section.getBlockState(lx, ly, lz);
						BlockPos pos = null;
						if (blockState.isRandomlyTicking()) {
							pos = new BlockPos(minX + lx, minYInSection + ly, minZ + lz);
							blockState.randomTick(self, pos, self.getRandom());
						}

						FluidState fluidState = blockState.getFluidState();
						if (fluidState.isRandomlyTicking()) {
							if (pos == null) pos = new BlockPos(minX + lx, minYInSection + ly, minZ + lz);
							fluidState.randomTick(self, pos, self.getRandom());
						}

						profiler.pop();
					}
				}
			}
		}

		profiler.pop();
	}
}
