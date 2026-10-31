package dev.cadence.shaders.cadence.worldgen;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.slf4j.LoggerFactory;

/**
 * Cadence: world generation's feature step (trees, ores, structures, lakes...) on every core.
 *
 * <p>Vanilla 26.3 already builds terrain (noise, surface, caves) on the worker pool, but applies every chunk's generation
 * steps one at a time on a single "worldgen" executor, and FEATURES does its work right there: exploring new land, one core
 * places all the features while the others wait. Here a FEATURES step is handed to the worker pool, holding its 3x3 chunk
 * region (the step's blockStateWriteRadius, which vanilla declares for exactly this) through RegionScheduler; every other
 * step still runs where vanilla runs it, holding its own chunk. A step never runs before or alongside an earlier step that
 * shares a chunk, so the chunks come out as if the steps had run one at a time in vanilla's order.
 *
 * <p>Why only the own chunk for the other steps: vanilla's dependencies already keep them off chunks a FEATURES step can
 * write, except INITIALIZE_LIGHT (no neighbour requirement), which the own-chunk key orders. Shared caches that vanilla
 * never had to share between features are made safe separately (StructureTemplate palette cache, per-StructureStart
 * placement).
 *
 * <p>OFF by default (-Dcadence.parallelWorldgen=true turns it on): measured on the M2 Air, 26.3 already builds
 * terrain — about 75% of generation's CPU — on every worker, generation runs at 4-5 cores, and with FEATURES moved off the
 * serial executor flying into new land at 20 blocks/s had fewer chunks ready ahead (5.0 vs 6.7 of 12) and forced areas
 * were no faster: each chunk's generation is a chain of waits, and the extra hand-off lengthens it.
 */
public final class ParallelWorldgen {
	/** Switchable at runtime only while no generation is running (self-test A/B between areas). */
	public static volatile boolean enabled = Boolean.getBoolean("cadence.parallelWorldgen");
	private static final boolean LOG = Boolean.getBoolean("cadence.probe") || System.getProperty("cadence.selftest") != null;
	private static long logged;
	private static volatile long unexpectedWaits;

	private ParallelWorldgen() {
	}

	/** Around ChunkMap.applyStep, on the worldgen executor. */
	public static CompletableFuture<ChunkAccess> apply(RegionScheduler scheduler, ChunkPos pos, ChunkStep step,
		Supplier<CompletableFuture<ChunkAccess>> original) {
		if (LOG && System.nanoTime() - logged > 30_000_000_000L) {
			logged = System.nanoTime();
			LoggerFactory.getLogger("cadence").info("Parallel worldgen: {}; other steps that had to wait: {}", scheduler.stats(), unexpectedWaits);
		}
		if (step.targetStatus() == ChunkStatus.FEATURES && step.blockStateWriteRadius() >= 0) {
			int r = step.blockStateWriteRadius();
			long[] keys = new long[(2 * r + 1) * (2 * r + 1)];
			int i = 0;
			for (int dx = -r; dx <= r; dx++)
				for (int dz = -r; dz <= r; dz++)
					keys[i++] = ChunkPos.pack(pos.x() + dx, pos.z() + dz);
			CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
			scheduler.submit(keys, task -> {
				try {
					original.get().whenComplete((chunk, failure) -> {
						if (failure != null) result.completeExceptionally(failure);
						else result.complete(chunk);
					});
				} catch (Throwable t) {
					result.completeExceptionally(t);
				} finally {
					scheduler.finish(task);
				}
			}, Util.backgroundExecutor().forName("cadenceFeatures"));
			return result;
		}
		long[] own = {pos.pack()};
		if (step.targetStatus() == ChunkStatus.INITIALIZE_LIGHT) {
			// The one step vanilla may apply to a chunk while a neighbour's FEATURES step still writes into it: if one does,
			// it runs right after that one instead of holding up the worldgen executor.
			CompletableFuture<ChunkAccess> later = new CompletableFuture<>();
			RegionScheduler.Task now = scheduler.submitNowOrLater(own, task -> {
				try {
					original.get().whenComplete((chunk, failure) -> {
						if (failure != null) later.completeExceptionally(failure);
						else later.complete(chunk);
					});
				} catch (Throwable t) {
					later.completeExceptionally(t);
				} finally {
					scheduler.finish(task);
				}
			}, Util.backgroundExecutor().forName("cadenceInitLight"));
			if (now == null) return later;
			try {
				return original.get();
			} finally {
				scheduler.finish(now);
			}
		}
		// Every other step: vanilla's dependencies keep it off chunks a running FEATURES step writes (counted if not).
		RegionScheduler.Task[] granted = new RegionScheduler.Task[1];
		java.util.concurrent.CountDownLatch free = new java.util.concurrent.CountDownLatch(1);
		RegionScheduler.Task task = scheduler.submitNowOrLater(own, t -> {
			granted[0] = t;
			free.countDown();
		}, Runnable::run);
		if (task == null) {
			unexpectedWaits++;
			try {
				free.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			task = granted[0];
		}
		try {
			return original.get();
		} finally {
			scheduler.finish(task);
		}
	}
}
