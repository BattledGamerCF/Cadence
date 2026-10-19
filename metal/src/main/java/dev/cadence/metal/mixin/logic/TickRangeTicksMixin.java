package dev.cadence.metal.mixin.logic;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cadence.metal.logic.TickRange;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.function.LongPredicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence Logic {@code tickRange} (TickRange): in LevelTicks.sortContainersToTick, "is this chunk ticking?" is skipped for a
 * container marked out of block-ticking range at the current version, and a container whose answer is "no" because it is
 * out of range is marked. A marked container is also parked: its entry moves from nextTickForContainer (walked every tick)
 * to a side map, with its time, so the walk doesn't visit it at all; at the first walk after the version moves every parked
 * entry goes back as it was. The walk's order doesn't matter (due containers go to a priority queue), and vanilla's own
 * writes and removals of an entry (a tick scheduled, a container added, removed or cleared) unpark it first, so the map is
 * always what vanilla's would be for every chunk that can tick.
 */
@Mixin(LevelTicks.class)
abstract class TickRangeTicksMixin implements TickRange.Owner {
	@Shadow @Final private Long2LongMap nextTickForContainer;
	@Unique private ServerLevel cadence$level;
	@Unique private final Long2LongOpenHashMap cadence$parked = new Long2LongOpenHashMap();
	@Unique private long cadence$parkedAt;

	@Override
	public ServerLevel cadence$level() {
		return this.cadence$level;
	}

	@Override
	public void cadence$level(ServerLevel level) {
		this.cadence$level = level;
	}

	/** Before the walk: a moved version puts every parked entry back. */
	@Inject(method = "sortContainersToTick", at = @At("HEAD"))
	private void cadence$unpark(long time, CallbackInfo ci) {
		if (this.cadence$parked.isEmpty() || this.cadence$parkedAt == TickRange.version) return;
		for (Long2LongMap.Entry e : this.cadence$parked.long2LongEntrySet()) this.nextTickForContainer.put(e.getLongKey(), e.getLongValue());
		this.cadence$parked.clear();
	}

	@WrapOperation(method = "sortContainersToTick", at = @At(value = "INVOKE", target = "Ljava/util/function/LongPredicate;test(J)Z"))
	private boolean cadence$tickRange(LongPredicate check, long chunk, Operation<Boolean> original, @Local LevelChunkTicks<?> container,
		@Local ObjectIterator<Long2LongMap.Entry> walk, @Local Long2LongMap.Entry entry) {
		ServerLevel level = this.cadence$level;
		if (level == null) return original.call(check, chunk);
		TickRange.Mark mark = (TickRange.Mark) container;
		long version = TickRange.version;
		if (mark.cadence$outOfRangeAt() != version) {
			boolean ticking = original.call(check, chunk);
			if (ticking || level.shouldTickBlocksAt(chunk) || TickRange.version != version) return ticking;
			mark.cadence$outOfRangeAt(version);
		}
		// out of range at this version: parked until it moves (the entry leaves through the walk's own iterator)
		if (!this.cadence$parked.isEmpty() && this.cadence$parkedAt != version) return false;  // (parked under another version: not mixed)
		this.cadence$parked.put(chunk, entry.getLongValue());
		this.cadence$parkedAt = version;
		walk.remove();
		return false;
	}

	/** Vanilla writes an entry (a container added, a new first tick): that chunk is no longer parked. */
	@WrapOperation(method = {"addContainer", "updateContainerScheduling"}, at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2LongMap;put(JJ)J"))
	private long cadence$unparkPut(Long2LongMap map, long chunk, long tick, Operation<Long> original) {
		if (map == this.nextTickForContainer && !this.cadence$parked.isEmpty()) this.cadence$parked.remove(chunk);
		return original.call(map, chunk, tick);
	}

	/** Vanilla removes an entry (a container removed or cleared): its parked copy goes too. */
	@WrapOperation(method = {"removeContainer", "lambda$clearArea$1"}, at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2LongMap;remove(J)J"))
	private long cadence$unparkRemove(Long2LongMap map, long chunk, Operation<Long> original) {
		if (map == this.nextTickForContainer && !this.cadence$parked.isEmpty()) this.cadence$parked.remove(chunk);
		return original.call(map, chunk);
	}
}
