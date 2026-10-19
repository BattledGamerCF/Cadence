package dev.cadence.metal.mixin.logic;

import dev.cadence.metal.logic.TickRange;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence Logic {@code tickRange} (TickRange): the level's block and fluid tick lists learn their level. */
@Mixin(ServerLevel.class)
abstract class TickRangeLevelMixin {
	@Shadow @Final private LevelTicks<Block> blockTicks;
	@Shadow @Final private LevelTicks<Fluid> fluidTicks;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void cadence$tickRangeOwner(CallbackInfo ci) {
		((TickRange.Owner) this.blockTicks).cadence$level((ServerLevel) (Object) this);
		((TickRange.Owner) this.fluidTicks).cadence$level((ServerLevel) (Object) this);
	}
}
