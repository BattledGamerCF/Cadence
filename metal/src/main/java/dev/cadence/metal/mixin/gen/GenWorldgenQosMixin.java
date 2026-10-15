// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.gen;

import dev.cadence.metal.lod.LodGenQos;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dcadence.gen.worldgenQos=CLASS: a thread filling a chunk from the noise puts itself in that QoS class (once). */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class GenWorldgenQosMixin {
	@Inject(method = "doFill", at = @At("HEAD"))
	private void cadence$qos(CallbackInfo ci) {
		LodGenQos.once(LodGenQos.WORLDGEN, "chunk generation");
	}
}
