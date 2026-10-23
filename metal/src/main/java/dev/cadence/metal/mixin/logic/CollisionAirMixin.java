package dev.cadence.metal.mixin.logic;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Cadence Logic (cadence.logic.collisionAir): every entity's movement walks the blocks its box sweeps (BlockCollisions), and
 * most of them are air: each one went through the collision context, the block state's shape query and a move of the empty
 * shape to its position. The game's air blocks have no collision shape in any context (none a context can make solid), so they
 * are answered at once with the empty shape the game itself returns; the walk then skips it exactly as before. Measured 10-23:
 * collisions were 11% of the server tick in a flight over new land with mobs about.
 */
@Mixin(BlockCollisions.class)
abstract class CollisionAirMixin {
	@WrapOperation(method = "computeNext", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/phys/shapes/CollisionContext;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
	private VoxelShape cadence$air(CollisionContext context, BlockState state, CollisionGetter level, BlockPos pos, Operation<VoxelShape> original) {
		// (the game's three air blocks, not every isAir(): a mod's block can be air and still collide, an invisible wall)
		net.minecraft.world.level.block.Block b = state.getBlock();
		return b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR ? Shapes.empty() : original.call(context, state, level, pos);
	}
}
