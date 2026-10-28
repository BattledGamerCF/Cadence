package dev.cadence.shaders.cadence.system;

import dev.cadence.shaders.cadence.ui.ShaderToggle;
import net.minecraft.client.Minecraft;

/**
 * Cadence: "In Background" (Video Settings). Vanilla only slows drawing when the window is minimized, or after a minute
 * without input (30 fps). On a Mac, switching to another app or another full-screen Space leaves Minecraft drawing at the
 * full Max Framerate behind it for that minute and at 30 fps after, which is heat and battery spent on frames nobody sees.
 * With this on (the default), while Minecraft isn't the active app it draws at most {@link #FPS} frames per second; the
 * game itself, the integrated server and sounds keep running exactly as before, and drawing is back to normal the moment
 * the window is active again. Only the frame-rate limit changes (FramerateCapMixin), never what is drawn.
 * Off: vanilla's behaviour. -Dcadence.backgroundFps=false, or the button.
 */
public final class BackgroundFps {
	public static final int FPS = 15;
	private static volatile boolean enabled = !"false".equalsIgnoreCase(
		System.getProperty("cadence.backgroundFps", ShaderToggle.configValue("backgroundFps", "true")).strip());
	/** Self-tests: behave as if the window weren't active. */
	public static volatile boolean fakeBackground;

	private BackgroundFps() {
	}

	public static boolean enabled() {
		return enabled;
	}

	public static void setEnabled(boolean on) {
		enabled = on;
		ShaderToggle.saveKey("backgroundFps", Boolean.toString(on));
	}

	/** The frame-rate limit this asks for, or Integer.MAX_VALUE for none. */
	public static int fpsCap() {
		Minecraft mc = Minecraft.getInstance();
		boolean background = fakeBackground || mc.getWindow() != null && !mc.getWindow().isFocused();
		// (a world open to LAN running in the background: App Nap would slow its ticks for the players in it; a world only you
		// play is left to nap, as In Background and Battery Saver want)
		var server = mc.getSingleplayerServer();
		dev.cadence.metal.MacSystem.appNapBusy(background && server != null && server.isPublished());
		if (!enabled) return Integer.MAX_VALUE;
		return background ? FPS : Integer.MAX_VALUE;
	}
}
