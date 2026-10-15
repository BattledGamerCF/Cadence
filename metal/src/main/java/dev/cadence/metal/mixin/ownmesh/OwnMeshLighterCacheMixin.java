// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.ownmesh;

import dev.cadence.metal.own.OwnRegion;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dcadence.own.mesh.region: while our mesher runs vanilla's renderers on an OwnRegion, the lighter's light and shade lookups are
 * served from the region's per-position arrays (same values as vanilla's 100-entry hash caches compute). Any other level:
 * untouched.
 */
@Mixin(BlockModelLighter.Cache.class)
abstract class OwnMeshLighterCacheMixin {
	@Inject(method = "getLightCoords", at = @At("HEAD"), cancellable = true)
	private void cadence$ownLight(BlockState state, BlockAndTintGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		OwnRegion r = level instanceof OwnRegion o ? o : OwnRegion.FRAPI_REGION ? OwnRegion.forRegion(level) : null;
		if (r != null) cir.setReturnValue(r.lightCoords(state, pos));
	}

	@Inject(method = "getShadeBrightness", at = @At("HEAD"), cancellable = true)
	private void cadence$ownShade(BlockState state, BlockAndTintGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		OwnRegion r = level instanceof OwnRegion o ? o : OwnRegion.FRAPI_REGION ? OwnRegion.forRegion(level) : null;
		if (r != null) cir.setReturnValue(r.shadeBrightness(state, pos));
	}
}
