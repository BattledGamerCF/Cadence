package dev.cadence.shaders.cadence.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cadence.shaders.cadence.mesh.SmartLeaves;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Cadence (SmartLeaves): a leaf face into a leaf that has another leaf behind it is skipped (opt-in). */
@Mixin(LeavesBlock.class)
public abstract class SmartLeavesMixin {
	@ModifyReturnValue(method = "skipRendering", at = @At("RETURN"))
	private boolean cadence$smart(boolean vanilla, BlockState state, BlockState neighbor, Direction direction) {
		return SmartLeaves.skip(vanilla, neighbor, direction);
	}
}
