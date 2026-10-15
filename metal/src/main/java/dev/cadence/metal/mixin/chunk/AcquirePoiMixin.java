// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.chunk;

import com.mojang.datafixers.util.Pair;
import java.util.function.Predicate;
import java.util.stream.Stream;
import dev.cadence.metal.chunk.PoiScan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * D1 (-Dcadence.chunk.poi=true): AcquirePoi's POI search (13% of the server thread on the Neo with 200 mobs) through PoiScan's
 * loops instead of nested streams. Only this call site: it consumes the stream immediately (see PoiScan for why that is exact).
 */
@Mixin(AcquirePoi.class)
abstract class AcquirePoiMixin {
	@Redirect(method = "lambda$create$3", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;findAllClosestFirstWithType(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;"))
	private static Stream<Pair<Holder<PoiType>, BlockPos>> cadence$scan(PoiManager poi, Predicate<Holder<PoiType>> predicate, Predicate<BlockPos> filter,
		BlockPos center, int radius, PoiManager.Occupancy occupancy) {
		if (dev.cadence.metal.chunk.ChunkOpt.POI_VERIFY) return PoiScan.verify(poi, predicate, filter, center, radius, occupancy);
		return PoiScan.closestFirstWithType(poi, predicate, filter, center, radius, occupancy);
	}
}
