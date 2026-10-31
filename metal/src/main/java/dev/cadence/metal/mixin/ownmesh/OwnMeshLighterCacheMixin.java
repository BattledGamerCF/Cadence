// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.ownmesh;

import dev.cadence.metal.own.OwnRegion;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

/**
 * -Dcadence.own.mesh.region: while our mesher runs vanilla's renderers on an OwnRegion, the lighter's light and shade lookups are
 * served from the region's per-position arrays (same values as vanilla's 100-entry hash caches compute). Any other level:
 * untouched. Wrapped, not a cancellable inject: that boxed every answer (a Float or an Integer per lookup, ~3% of everything
 * allocated while flying over new terrain).
 */
@Mixin(BlockModelLighter.Cache.class)
abstract class OwnMeshLighterCacheMixin {
	@WrapMethod(method = "getLightCoords")
	private int cadence$ownLight(BlockState state, BlockAndTintGetter level, BlockPos pos, Operation<Integer> original) {
		OwnRegion r = level instanceof OwnRegion o ? o : OwnRegion.FRAPI_REGION ? OwnRegion.forRegion(level) : null;
		return r != null ? r.lightCoords(state, pos) : original.call(state, level, pos);
	}

	@WrapMethod(method = "getShadeBrightness")
	private float cadence$ownShade(BlockState state, BlockAndTintGetter level, BlockPos pos, Operation<Float> original) {
		OwnRegion r = level instanceof OwnRegion o ? o : OwnRegion.FRAPI_REGION ? OwnRegion.forRegion(level) : null;
		return r != null ? r.shadeBrightness(state, pos) : original.call(state, level, pos);
	}
}
