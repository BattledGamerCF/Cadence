package dev.cadence.metal.mixin.logic;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Cadence Logic {@code blockEntityTick}: vanilla removes each removed block-entity ticker with Iterator.remove on an
 * ArrayList, shifting the rest of the list every time; unloading an area with thousands of block entities (flying past a
 * big base) costs time quadratic in their number, all in one tick. This makes one pass that moves each kept ticker down to
 * its place and trims the tail once. Tickers are checked, ticked and kept in the same order, a ticker removed by an earlier
 * one's tick is dropped when it is reached, as vanilla; tickers added during the pass go to the pending list, as vanilla.
 */
@Mixin(Level.class)
abstract class LevelBlockEntityTickMixin {
	@Shadow @Final protected List<TickingBlockEntity> blockEntityTickers;
	@Shadow @Final private List<TickingBlockEntity> pendingBlockEntityTickers;
	@Shadow private boolean tickingBlockEntities;

	@Shadow public abstract TickRateManager tickRateManager();

	@Shadow public abstract boolean shouldTickBlocksAt(BlockPos pos);

	/**
	 * @author Cadence
	 * @reason one compacting pass instead of an ArrayList removal (a shift of the whole tail) per removed ticker
	 */
	@Overwrite
	public void tickBlockEntities() {
		this.tickingBlockEntities = true;
		if (!this.pendingBlockEntityTickers.isEmpty()) {
			this.blockEntityTickers.addAll(this.pendingBlockEntityTickers);
			this.pendingBlockEntityTickers.clear();
		}
		List<TickingBlockEntity> tickers = this.blockEntityTickers;
		boolean runsNormally = this.tickRateManager().runsNormally();
		int size = tickers.size(), next = 0, kept = 0;
		try {
			while (next < size) {
				TickingBlockEntity ticker = tickers.get(next++);
				if (ticker.isRemoved()) continue;
				if (kept != next - 1) tickers.set(kept, ticker);
				kept++;
				if (runsNormally && this.shouldTickBlocksAt(ticker.getPos())) ticker.tick();
			}
		} finally {
			if (kept != next) {
				// the unvisited rest (all of it unless a tick threw) moves down behind the kept ones; the tail goes
				for (int i = next; i < size; i++) tickers.set(kept++, tickers.get(i));
				tickers.subList(kept, size).clear();
			}
		}
		this.tickingBlockEntities = false;
	}
}
