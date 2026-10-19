package dev.cadence.metal.mixin.logic;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cadence.metal.logic.TickRange;
import java.util.function.LongPredicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Cadence Logic {@code tickRange} (TickRange): LevelTicks.sortContainersToTick's "is this chunk ticking?" skipped for a container
 * marked out of block-ticking range at the current version; a container whose answer is "no" because it is out of range is
 * marked (one more range lookup, once per version, only when the answer was already "no").
 */
@Mixin(LevelTicks.class)
abstract class TickRangeTicksMixin implements TickRange.Owner {
	@Unique private ServerLevel cadence$level;

	@Override
	public ServerLevel cadence$level() {
		return this.cadence$level;
	}

	@Override
	public void cadence$level(ServerLevel level) {
		this.cadence$level = level;
	}

	@WrapOperation(method = "sortContainersToTick", at = @At(value = "INVOKE", target = "Ljava/util/function/LongPredicate;test(J)Z"))
	private boolean cadence$tickRange(LongPredicate check, long chunk, Operation<Boolean> original, @Local LevelChunkTicks<?> container) {
		ServerLevel level = this.cadence$level;
		if (level == null) return original.call(check, chunk);
		TickRange.Mark mark = (TickRange.Mark) container;
		long version = TickRange.version;
		if (mark.cadence$outOfRangeAt() == version) return false;
		boolean ticking = original.call(check, chunk);
		if (!ticking && !level.shouldTickBlocksAt(chunk) && TickRange.version == version) mark.cadence$outOfRangeAt(version);
		return ticking;
	}
}
