// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.edge;

import java.util.concurrent.CompletableFuture;
import dev.cadence.metal.EdgeCap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStatusTasks.class)
abstract class GenSignalMixin {
	// every generation step (never run for a chunk loaded from disk at FULL); chunks saved part-generated, as at the edge of an
	// explored area, resume at a later step without generateStructureStarts
	@Inject(method = {"generateStructureStarts", "generateStructureReferences", "generateBiomes", "buildTerrain", "generateFeatures"}, at = @At("HEAD"))
	private static void cadence$generating(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		if (EdgeCap.ON) EdgeCap.generating(chunk.getPos());
	}
}
