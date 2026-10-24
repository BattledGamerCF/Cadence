package dev.cadence.shaders.cadence.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.cadence.shaders.cadence.system.HeatControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Cadence: the frame-rate limit lowered while the Mac is warm (HeatControl) and while Minecraft isn't the active app (BackgroundFps). */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateCapMixin {
	private static final boolean DISPLAY_CAP = !"false".equals(System.getProperty("cadence.shaders.displayCap"));

	@ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
	private int cadence$heatCap(int limit) {
		if (dev.cadence.shaders.cadence.setup.QuickSetup.testing) return Math.min(limit, 10);
		if (dev.cadence.shaders.cadence.render.MaxFpsMode.on() || dev.cadence.metal.record.Record.chosen()) {
			// Render Max FPS: nothing holds the frame rate back (Max Framerate, Heat Control, On Battery) while the game is the
			// active app; In Background still applies when another app is (no frame rate is measured then that matters)
			int background = dev.cadence.shaders.cadence.system.BackgroundFps.fpsCap();
			dev.cadence.shaders.cadence.system.FramePacing.cap(background);
			return background;
		}
		int cap = Math.min(limit, Math.min(HeatControl.fpsCap(), Math.min(dev.cadence.shaders.cadence.system.BackgroundFps.fpsCap(),
			dev.cadence.shaders.cadence.system.BatteryMode.fpsCap())));
		// Cadence: a shader pack on a display of 60 Hz or less runs at the display's rate by default: frames past it are never
		// shown, and a pack's frame is expensive (heat, battery). -Dcadence.shaders.displayCap=false: as the options say.
		// (a pack is installed: "a pack frame is being drawn" was never true here, the limit is read after the frame, BH2-08)
		if (DISPLAY_CAP && dev.cadence.shaders.runtime.ShaderRuntime.get() != null && HeatControl.refreshHz() <= 60)
			cap = Math.min(cap, HeatControl.refreshHz());
		dev.cadence.shaders.cadence.system.FramePacing.cap(cap);
		return cap;
	}
}
