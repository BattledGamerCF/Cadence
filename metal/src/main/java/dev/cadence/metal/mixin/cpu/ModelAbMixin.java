// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.cpu;

import dev.cadence.metal.cpu.Ab;
import dev.cadence.metal.cpu.Cpu;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dcadence.cpu.model=ab: times FeatureRenderDispatcher.prepareFrame (where models are built) with the lever alternately off/on. */
@Mixin(FeatureRenderDispatcher.class)
abstract class ModelAbMixin {
	@Unique private static final Ab cadence$ab = new Ab("model.prepareFrame");

	@Inject(method = "prepareFrame", at = @At("HEAD"))
	private void cadence$begin(CallbackInfoReturnable<?> cir) {
		if (Cpu.MODEL_AB) Cpu.modelActive = cadence$ab.begin();
	}

	@Inject(method = "prepareFrame", at = @At("RETURN"))
	private void cadence$end(CallbackInfoReturnable<?> cir) {
		if (Cpu.MODEL_AB) cadence$ab.end();
	}
}
