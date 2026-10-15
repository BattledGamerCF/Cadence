package dev.cadence.shaders.cadence.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.cadence.shaders.cadence.system.HeatControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Cadence: the frame-rate limit lowered while the Mac is warm (HeatControl) and while Minecraft isn't the active app (BackgroundFps). */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateCapMixin {
	@ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
	private int cadence$heatCap(int limit) {
		if (dev.cadence.shaders.cadence.setup.QuickSetup.testing) return Math.min(limit, 10);
		return Math.min(limit, Math.min(HeatControl.fpsCap(), dev.cadence.shaders.cadence.system.BackgroundFps.fpsCap()));
	}
}
