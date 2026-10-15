// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.ownmesh;

import dev.cadence.metal.own.OwnRegion;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dcadence.own.mesh.frapiRegion: while our compile runs on this region (Fabric API's renderer reads it), block and fluid states
 * come from the compile's OwnRegion cache: the same states, each read from the section copy once. Any other use: untouched.
 */
@Mixin(RenderSectionRegion.class)
abstract class OwnMeshRegionStateMixin {
	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void cadence$ownState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		OwnRegion r = OwnRegion.forRegion(this);
		if (r != null) cir.setReturnValue(r.getBlockState(pos));
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void cadence$ownFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		OwnRegion r = OwnRegion.forRegion(this);
		if (r != null) cir.setReturnValue(r.getFluidState(pos));
	}
}
