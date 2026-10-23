package dev.cadence.shaders.cadence.render;

/**
 * Cadence: "Render Max FPS" (Video Settings), for curiosity: how fast can Minecraft draw frames at all? A black screen with two
 * numbers, and as little work per frame as the game allows:
 * <ul>
 * <li>the world isn't drawn or gathered for drawing (GameRenderer.renderLevel, LevelExtractor.extract), nor the camera and fog
 * worked out for it (GameRenderer.extractCamera); a shader pack runs none of its passes;</li>
 * <li>the HUD isn't built (hotbar, crosshair, the fps counter): in its place the peak frame rate of this visit and the best of
 * the session;</li>
 * <li>nothing holds the frame rate back: no frame-rate limit (Max Framerate, Heat Control, On Battery), no vsync (frames past
 * one a refresh aren't presented at all).</li>
 * </ul>
 * The game keeps running underneath (the server ticks, chunks load, mobs move). Not saved: every launch starts with it off.
 * MaxFpsModeMixin, MaxFpsModeHudMixin, ShaderGameRendererMixin, FramerateCapMixin.
 */
public final class MaxFpsMode {
	private static volatile boolean on;

	private MaxFpsMode() {
	}

	public static boolean on() {
		// (not while a world or dimension is loading: the loading screen waits for the player's chunk to be built, which needs
		// the world gathered and drawn; skipped, it held "Loading terrain..." for vanilla's full 30 s timeout at every join)
		return on && !(net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen);
	}

	/** The button's setting (on even while a world loads). */
	public static boolean chosen() {
		return on;
	}

	public static void set(boolean value) {
		if (value == on) return;
		on = value;
		peak = 0;
		peakLabel = "Peak: measuring...";
		filled = 0;
		bucketFrames = 0;
		bucketStart = 0;
		try {
			dev.cadence.metal.MetalBridge.uncappedPresent(value);
		} catch (RuntimeException | LinkageError e) {
			// not on the Metal backend: vsync stays as the option says
		}
	}

	// ---- the frame rate: the frames of the last second, measured every quarter second (render thread) ----

	private static final long BUCKET = 250_000_000L;
	private static final int BUCKETS = 4;
	private static final int[] frames = new int[BUCKETS];
	private static final long[] starts = new long[BUCKETS];
	private static int next, filled, bucketFrames;
	private static long bucketStart;
	private static int peak, best;
	private static String peakLabel = "Peak: measuring...", bestLabel = "Session best: -";

	/**
	 * A frame drawn in this mode (the HUD's turn): counted; each quarter second the last second's frame rate, kept when it's a new
	 * peak. The labels change only then, so the text's own cost doesn't grow with the frame rate.
	 */
	public static void frame() {
		long now = System.nanoTime();
		if (bucketStart == 0) {
			bucketStart = now;
			return;
		}
		bucketFrames++;
		if (now - bucketStart < BUCKET) return;
		frames[next] = bucketFrames;
		starts[next] = bucketStart;
		next = (next + 1) % BUCKETS;
		filled++;
		bucketFrames = 0;
		bucketStart = now;
		// (the first second in the mode is the switch into it: not counted)
		if (filled <= BUCKETS) return;
		long total = 0;
		for (int f : frames) total += f;
		long span = now - starts[next];  // (the oldest bucket's start: next is where the oldest one sits)
		int fps = (int) (total * 1_000_000_000L / Math.max(1L, span));
		if (fps > peak) {
			peak = fps;
			peakLabel = "Peak: " + fps + " fps";
		}
		if (fps > best) {
			best = fps;
			bestLabel = "Session best: " + fps + " fps";
		}
	}

	/** "Peak: 9812 fps": the highest frame rate (over a second) since Render Max FPS was turned on. */
	public static String peakLabel() {
		return peakLabel;
	}

	/** "Session best: 10034 fps": the highest in this mode since the game started. */
	public static String bestLabel() {
		return bestLabel;
	}
}
