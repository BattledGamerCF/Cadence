// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.cpu;

import dev.cadence.metal.cpu.Ab;
import dev.cadence.metal.cpu.EntityBox;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dcadence.cpu.entityBox=ab: times the frame's entity pass with the box reuse alternately off and on (see Ab). */
@Mixin(LevelExtractor.class)
abstract class EntityBoxAbMixin {
	@Unique private static final Ab cadence$ab = new Ab("entityBox.extractVisibleEntities");

	@Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void cadence$begin(CallbackInfo ci) {
		if (EntityBox.AB) EntityBox.active = cadence$ab.begin();
	}

	@Inject(method = "extractVisibleEntities", at = @At("RETURN"))
	private void cadence$end(CallbackInfo ci) {
		if (EntityBox.AB) cadence$ab.end();
	}
}
