package dev.cadence.shaders.cadence.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Cadence (ParallelWorldgen): a structure spans several chunks and its pieces keep state between the chunks that place them
 * (a temple's measured ground height, a mineshaft's "spawner placed"); chunks place one structure one at a time, as they
 * did when all features ran on one thread. Different structures still place in parallel.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartPlaceMixin {
	@WrapMethod(method = "placeInChunk")
	private void cadence$onePlacerAtATime(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
		BoundingBox box, ChunkPos chunk, Operation<Void> original) {
		synchronized (this) {
			original.call(level, structures, generator, random, box, chunk);
		}
	}
}
