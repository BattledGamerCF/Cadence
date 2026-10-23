package dev.cadence.metal.mixin.lod;

import dev.cadence.metal.lod.Lod;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.state.BeaconRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Far terrain's beacon beams (LodBeacons): every beacon the game draws is remembered, with its beam as drawn. */
@Mixin(BeaconRenderer.class)
abstract class LodBeaconSeenMixin {
	@Inject(method = "extract", at = @At("TAIL"))
	private static void cadence$lodBeaconSeen(BlockEntity beacon, BeaconRenderState state, float partialTicks, Vec3 camera, CallbackInfo ci) {
		Lod.beaconSeen(beacon);
	}
}
