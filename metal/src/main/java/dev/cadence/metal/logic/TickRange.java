package dev.cadence.metal.logic;

/**
 * Cadence Logic {@code tickRange}: scheduled block and fluid ticks (water flowing, leaves, redstone) are kept per chunk, and
 * every tick vanilla's LevelTicks asks, for every chunk with one due, whether that chunk is ticking (its simulation level,
 * its entities loaded, its ticking future). Chunks just outside the simulation distance keep due ticks that never run, so the
 * same "no" is worked out again every tick: in a test world by a river that was 15% of the server thread. The answer is "no"
 * whenever the chunk's simulation level isn't block-ticking, and that level changes only in SimulationChunkTracker.setLevel.
 * So a chunk's tick container remembers "out of block-ticking range at version N", the version moves at every level change
 * (any chunk, any dimension), and while it hasn't moved the container is skipped as vanilla would skip it. Containers that
 * fail for another reason (entities not loaded yet, ticking future not done) are asked every tick, as before.
 */
public final class TickRange {
	/** Moves at every simulation-level change (TickRangeTrackerMixin). Starts at 1: a container's 0 means "never marked". */
	public static volatile long version = 1;

	private TickRange() {
	}

	/** A tick container's mark (TickRangeChunkTicksMixin). */
	public interface Mark {
		long cadence$outOfRangeAt();

		void cadence$outOfRangeAt(long version);
	}

	/** A LevelTicks' server level (set as the ServerLevel is made; null for any other LevelTicks). */
	public interface Owner {
		net.minecraft.server.level.ServerLevel cadence$level();

		void cadence$level(net.minecraft.server.level.ServerLevel level);
	}
}
