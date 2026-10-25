package dev.cadence.shaders.cadence.render;

import dev.cadence.shaders.cadence.ui.ShaderToggle;
import net.minecraft.client.Minecraft;

/**
 * Video Settings > Auto Render Distance. With a shader pack and Auto resolution (RenderScale's window drives it): when the frame
 * rate stays under Auto's goal although the resolution is already as low as Auto takes it and the GPU is the reason, the
 * render distance comes down 2 chunks (not below 8); once the GPU has had plenty of room for a
 * minute it goes back up 2 at a time, never past the distance the player chose. A change makes the game build the view's
 * chunks again, so changes are rare: at most one every 20 s down, after 60 calm seconds up, none while chunks are building.
 * Far terrain fills past the distance either way. Off by default (autoRenderDistance=true in the config: on); turning it off
 * puts the player's distance back.
 */
public final class AutoRenderDistance {
	private static final int MIN = 8, STEP = 2;
	private static volatile boolean on = "true".equalsIgnoreCase(ShaderToggle.configValue("autoRenderDistance", "false").strip());
	/** The distance the player chose (Video Settings' slider): the ceiling. */
	private static int chosen = -1;
	private static int slowWindows, calmWindows;
	private static long lastChange;
	/** The distance this set last (a different one in the options means the player moved the slider: their new choice). */
	private static int applied = -1;

	private AutoRenderDistance() {
	}

	public static boolean on() {
		return on;
	}

	public static void toggle() {
		on = !on;
		ShaderToggle.saveKey("autoRenderDistance", Boolean.toString(on));
		if (!on && chosen > 0) set(chosen);
		chosen = -1;
		applied = -1;
		slowWindows = calmWindows = 0;
	}

	/**
	 * RenderScale's once-a-second window (render thread): the frame interval and GPU time against the goal, whether the
	 * resolution can still come down, whether chunks are building.
	 */
	static void window(double intervalMs, double gpuMs, double targetMs, boolean resolutionCanDrop, boolean chunksBuilding, long now) {
		if (!on) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		int rd = mc.options.renderDistance().get();
		if (chosen < 0 || rd != applied) chosen = rd;  // (the player's choice, or a new one they made with the slider)
		boolean slow = intervalMs > targetMs * 1.10 && gpuMs > targetMs * 0.85 && !resolutionCanDrop;
		boolean calm = gpuMs < targetMs * 0.55 && intervalMs < targetMs * 1.05;
		slowWindows = slow ? slowWindows + 1 : 0;
		calmWindows = calm ? calmWindows + 1 : 0;
		if (chunksBuilding) return;
		if (slowWindows >= 3 && rd > MIN && now - lastChange > 20_000_000_000L) {
			set(Math.max(MIN, rd - STEP));
			lastChange = now;
			slowWindows = calmWindows = 0;
		} else if (calmWindows >= 60 && rd < chosen && now - lastChange > 20_000_000_000L) {
			set(Math.min(chosen, rd + STEP));
			lastChange = now;
			calmWindows = 0;
		}
	}

	private static void set(int rd) {
		Minecraft mc = Minecraft.getInstance();
		mc.options.renderDistance().set(rd);
		applied = rd;
		System.out.println("[cadence] Auto Render Distance: " + rd + " chunks");
	}

	/**
	 * Twice a second (FramerateCapMixin): with no shader pack running, Auto has nothing to measure (it follows Auto resolution's
	 * pack frames): a distance it lowered goes back to the player's.
	 */
	public static void check() {
		if (!on || chosen < 0 || applied < 0 || dev.cadence.shaders.runtime.ShaderRuntime.get() != null) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.options.renderDistance().get() == applied && applied < chosen) set(chosen);
		chosen = -1;
		applied = -1;
	}

	/** The distance in use while Auto holds it under the player's (for the button), or -1. */
	public static int holding() {
		Minecraft mc = Minecraft.getInstance();
		int rd = mc.options.renderDistance().get();
		return on && chosen > 0 && rd < chosen ? rd : -1;
	}
}
