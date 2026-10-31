package dev.cadence.metal.mixin.logic;

import dev.cadence.metal.logic.TickRange;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Cadence Logic {@code tickRange} (TickRange): a chunk tick container's "out of block-ticking range" mark. */
@Mixin(LevelChunkTicks.class)
abstract class TickRangeChunkTicksMixin implements TickRange.Mark {
	@Unique private long cadence$outOfRangeAt;

	@Override
	public long cadence$outOfRangeAt() {
		return this.cadence$outOfRangeAt;
	}

	@Override
	public void cadence$outOfRangeAt(long version) {
		this.cadence$outOfRangeAt = version;
	}
}
