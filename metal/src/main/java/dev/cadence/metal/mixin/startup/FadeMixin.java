// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.startup;

import dev.cadence.metal.system.Startup;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** -Dcadence.startup.fadeMs=N: the loading overlay's fade-out (1000 ms) and fade-in (500 ms) scaled to N and N/2. */
@Mixin(LoadingOverlay.class)
abstract class FadeMixin {
	@ModifyConstant(method = "extractRenderState", require = 0, constant = @Constant(floatValue = 1000.0f))
	private float cadence$fadeOut(float v) {
		return Math.max(1, Startup.FADE_MS);
	}

	@ModifyConstant(method = "extractRenderState", require = 0, constant = @Constant(floatValue = 500.0f))
	private float cadence$fadeIn(float v) {
		return Math.max(1, Startup.FADE_MS / 2);
	}
}
