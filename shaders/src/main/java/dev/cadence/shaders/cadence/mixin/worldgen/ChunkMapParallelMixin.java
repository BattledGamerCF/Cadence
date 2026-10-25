package dev.cadence.shaders.cadence.mixin.worldgen;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cadence.shaders.cadence.worldgen.ParallelWorldgen;
import dev.cadence.shaders.cadence.worldgen.RegionScheduler;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Cadence: generation steps through the region scheduler (ParallelWorldgen). */
@Mixin(ChunkMap.class)
public abstract class ChunkMapParallelMixin {
	@Unique
	private final RegionScheduler cadence$regions = new RegionScheduler();

	@WrapMethod(method = "applyStep")
	private CompletableFuture<ChunkAccess> cadence$parallelStep(GenerationChunkHolder holder, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> cache, Operation<CompletableFuture<ChunkAccess>> original) {
		if (!ParallelWorldgen.enabled) return original.call(holder, step, cache);
		return ParallelWorldgen.apply(cadence$regions, holder.getPos(), step, () -> original.call(holder, step, cache));
	}
}
