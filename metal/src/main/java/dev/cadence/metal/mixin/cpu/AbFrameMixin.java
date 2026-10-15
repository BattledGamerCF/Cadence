// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.cpu;

import dev.cadence.metal.cpu.Ab;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Any -Dcadence.cpu.*=ab: counts frames for Ab's per-frame arms. */
@Mixin(GameRenderer.class)
abstract class AbFrameMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void cadence$frame(CallbackInfo ci) {
		Ab.frame++;
		if (dev.cadence.metal.cpu.Cpu.AB_SHOTS) dev.cadence.metal.cpu.AbShots.frameStart();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void cadence$frameEnd(CallbackInfo ci) {
		if (dev.cadence.metal.cpu.Cpu.AB_SHOTS) dev.cadence.metal.cpu.AbShots.frameEnd();
	}
}
