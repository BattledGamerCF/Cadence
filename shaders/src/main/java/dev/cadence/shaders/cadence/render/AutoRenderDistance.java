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
	/**
	 * The distance Auto holds the view to (-1: none). Only the effective distance is lowered (Options.getEffectiveRenderDistance,
	 * AutoRenderDistanceMixin), never the player's option: lowered there, it was saved to options.txt with the next options
	 * screen and stayed after a restart (BUG-HUNT BH4-03).
	 */
	private static volatile int cap = -1;
	private static int slowWindows, calmWindows;
	private static long lastChange;

	private AutoRenderDistance() {
	}

	public static boolean on() {
		return on;
	}

	public static void toggle() {
		on = !on;
		ShaderToggle.saveKey("autoRenderDistance", Boolean.toString(on));
		release();
	}

	/** Options.getEffectiveRenderDistance's value with Auto's cap. */
	public static int effective(int distance) {
		int c = cap;
		return c > 0 && c < distance ? c : distance;
	}

	/**
	 * RenderScale's once-a-second window (render thread): the frame interval and GPU time against the goal, whether the
	 * resolution can still come down, whether chunks are building.
	 */
	static void window(double intervalMs, double gpuMs, double targetMs, boolean resolutionCanDrop, boolean chunksBuilding, long now) {
		if (!on) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		int chosen = mc.options.renderDistance().get(), rd = effective(mc.options.getEffectiveRenderDistance());
		boolean slow = intervalMs > targetMs * 1.10 && gpuMs > targetMs * 0.85 && !resolutionCanDrop;
		boolean calm = gpuMs < targetMs * 0.55 && intervalMs < targetMs * 1.05;
		slowWindows = slow ? slowWindows + 1 : 0;
		calmWindows = calm ? calmWindows + 1 : 0;
		if (chunksBuilding) return;
		if (slowWindows >= 3 && rd > MIN && now - lastChange > 20_000_000_000L) {
			set(Math.max(MIN, rd - STEP), chosen);
			lastChange = now;
			slowWindows = calmWindows = 0;
		} else if (calmWindows >= 60 && cap > 0 && now - lastChange > 20_000_000_000L) {
			set(rd + STEP, chosen);
			lastChange = now;
			calmWindows = 0;
		}
	}

	private static void set(int rd, int chosen) {
		cap = rd >= chosen ? -1 : rd;
		System.out.println("[cadence] Auto Render Distance: " + Math.min(rd, chosen) + " chunks");
	}

	/** The player's distance again (off, no pack, a world closed). */
	private static void release() {
		cap = -1;
		slowWindows = calmWindows = 0;
	}

	/**
	 * Once a frame (FramerateCapMixin): with no shader pack running, Auto has nothing to measure (it follows Auto resolution's
	 * pack frames): the player's distance comes back.
	 */
	public static void check() {
		if (cap > 0 && (!on || dev.cadence.shaders.runtime.ShaderRuntime.get() == null || Minecraft.getInstance().level == null)) release();
	}

	/** The distance in use while Auto holds it under the player's (for the button), or -1. */
	public static int holding() {
		return cap;
	}
}
