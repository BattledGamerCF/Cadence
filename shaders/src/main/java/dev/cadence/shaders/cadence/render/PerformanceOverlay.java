package dev.cadence.shaders.cadence.render;

import dev.cadence.shaders.cadence.ui.ShaderToggle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Video Settings > Performance Overlay: a few lines under the fps counter while you play: the frames on screen and rendered
 * (Frame Generation), the frame time and the GPU's share of it, the render scale, the Mac's heat and power draw, and on battery
 * how long it lasts at this rate. Rebuilt twice a second (PerformanceOverlayMixin draws it). Off by default (performanceOverlay=).
 */
public final class PerformanceOverlay {
	private static volatile boolean on = "true".equalsIgnoreCase(ShaderToggle.configValue("performanceOverlay", "false").strip());
	private static List<String> lines = List.of();
	private static long builtAt;

	private PerformanceOverlay() {
	}

	public static boolean on() {
		return on;
	}

	public static void toggle() {
		on = !on;
		ShaderToggle.saveKey("performanceOverlay", Boolean.toString(on));
	}

	/** The lines to draw this frame (render thread). */
	public static List<String> lines(int renderedFps) {
		long now = System.nanoTime();
		if (now - builtAt < 500_000_000L) return lines;
		builtAt = now;
		List<String> l = new ArrayList<>();
		int shown = dev.cadence.metal.framegen.FrameGen.shownFps(renderedFps);
		l.add(shown > renderedFps ? "Frames: " + shown + " shown, " + renderedFps + " rendered (" + dev.cadence.metal.framegen.FrameGen.effective().label + ")"
			: "Frames: " + renderedFps + " rendered");
		double interval = RenderScale.lastIntervalMs(), gpu = RenderScale.lastGpuMs();
		if (interval > 0) l.add(String.format(Locale.ROOT, "Frame %.2f ms, GPU %.2f ms, scale %d%%", interval, gpu, Math.round(RenderScale.get() * 100)));
		double watts = dev.cadence.metal.MacSystem.systemWatts();
		l.add("Mac: " + dev.cadence.shaders.cadence.system.HeatControl.thermalWords() + (watts > 0 ? String.format(Locale.ROOT, ", %.1f W", watts) : ""));
		String battery = dev.cadence.metal.MacSystem.batteryTimeLeft();
		if (!battery.isEmpty()) l.add("Battery: about " + battery + " left");
		if (dev.cadence.shaders.cadence.system.BatteryMode.saving()) l.add("Saving power (Battery Saver" + (dev.cadence.shaders.cadence.system.BatteryMode.lowPower() ? ", Low Power Mode" : "") + ")");
		return lines = l;
	}
}
