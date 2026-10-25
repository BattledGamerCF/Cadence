package dev.cadence.shaders.cadence.mixin.system;

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
		dev.cadence.shaders.cadence.render.AutoRenderDistance.check();
		dev.cadence.shaders.cadence.server.ServerChunks.check();
		// (Photo Mode's countdown runs on ticks without a screen: one opening mid-countdown cancels the shot, BH4-14)
		if (dev.cadence.shaders.cadence.render.PhotoMode.active() && net.minecraft.client.Minecraft.getInstance().gui.screen() != null)
			dev.cadence.shaders.cadence.render.PhotoMode.cancel(net.minecraft.client.Minecraft.getInstance());
		if ((dev.cadence.shaders.cadence.render.MaxFpsMode.on() || dev.cadence.metal.record.Record.chosen())
			&& net.minecraft.client.Minecraft.getInstance().level != null) {
			// Render Max FPS: nothing holds the frame rate back (Max Framerate, Heat Control, On Battery) while the game is the
			// active app; In Background still applies when another app is (no frame rate is measured then that matters).
			// Only in a world: the title screen and menus keep the usual limits (BH3 minor: they ran uncapped, hot, for nothing)
			int background = dev.cadence.shaders.cadence.system.BackgroundFps.fpsCap();
			dev.cadence.metal.framegen.FrameGen.target(0, 0);
			dev.cadence.shaders.cadence.system.FramePacing.cap(background);
			return background;
		}
		int cap = Math.min(limit, Math.min(HeatControl.fpsCap(), Math.min(dev.cadence.shaders.cadence.system.BackgroundFps.fpsCap(),
			dev.cadence.shaders.cadence.system.BatteryMode.fpsCap())));
		// Cadence: a shader pack on a display of 60 Hz or less runs at the display's rate by default: frames past it are never
		// shown, and a pack's frame is expensive (heat, battery). -Dcadence.shaders.displayCap=false: as the options say.
		// (a pack is installed: "a pack frame is being drawn" was never true here, the limit is read after the frame)
		if (DISPLAY_CAP && dev.cadence.shaders.runtime.ShaderRuntime.get() != null && HeatControl.refreshHz() <= 60)
			cap = Math.min(cap, HeatControl.refreshHz());
		// Frame Generation: render at the base rate (Battery Saver 30, else 60, lower when another cap is) and show twice that, up
		// to the display's rate; only where that is more than the base (on a 60 Hz screen: Battery Saver 30 -> 60, the others a
		// true 60). Not with Max FPS (as many real frames as the Mac makes) or outside a world.
		// (and not while vanilla holds the rate down itself: idle (its Inactivity FPS Limit), minimized, menus)
		if (dev.cadence.metal.framegen.FrameGen.mode() != dev.cadence.metal.framegen.FrameGen.Mode.OFF && !dev.cadence.shaders.cadence.render.PhotoMode.active()
			&& net.minecraft.client.Minecraft.getInstance().level != null
			&& ((FramerateLimitTracker) (Object) this).getThrottleReason() == FramerateLimitTracker.FramerateThrottleReason.NONE) {
			long now = System.nanoTime();
			if (now - cadence$presetChecked > 2_000_000_000L) {  // (reads the config file: every 2 s, BH4-13)
				cadence$presetChecked = now;
				var preset = dev.cadence.shaders.cadence.ui.CadenceSettings.current();
				cadence$fgAllowed = preset != dev.cadence.shaders.cadence.ui.CadenceSettings.Preset.MAX_FPS;
				cadence$saver = preset == dev.cadence.shaders.cadence.ui.CadenceSettings.Preset.BATTERY
					|| dev.cadence.shaders.cadence.system.BatteryMode.saving();
			}
			int forced = dev.cadence.metal.framegen.FrameGen.forceBase;
			int hz = HeatControl.refreshHz(), base = Math.min(forced > 0 ? forced : cadence$saver ? 30 : 60, cap), shown = 2 * base;
			// (shown exactly twice the frames rendered, and only where the screen shows that many: otherwise every frame rendered.
			// A rate between, 90 shown of 60 rendered on a 90 Hz screen, gave real and generated frames uneven times on screen,
			// BUG-HUNT BH4-05; on a 60 Hz screen Recommended stays a true 60)
			if (shown > hz) shown = base;
			dev.cadence.metal.framegen.FrameGen.target(cadence$fgAllowed ? base : 0, shown);
			if (dev.cadence.metal.framegen.FrameGen.running()) {
				dev.cadence.shaders.cadence.system.FramePacing.cap(shown);
				return base;
			}
		} else {
			dev.cadence.metal.framegen.FrameGen.target(0, 0);
		}
		dev.cadence.shaders.cadence.system.FramePacing.cap(cap);
		return cap;
	}

	@org.spongepowered.asm.mixin.Unique private static long cadence$presetChecked;
	@org.spongepowered.asm.mixin.Unique private static boolean cadence$fgAllowed = true, cadence$saver;
}
