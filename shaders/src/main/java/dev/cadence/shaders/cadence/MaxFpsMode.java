package dev.cadence.shaders.cadence;

/**
 * Cadence: "Render Max FPS" (Video Settings), for curiosity: how fast can Minecraft draw frames at all? The world isn't drawn
 * (GameRenderer.renderLevel skipped: no block faces, sky, clouds, mobs, particles or hand) and isn't gathered for drawing
 * (LevelExtractor.extract skipped), so each frame is the screen cleared to sky blue, the HUD, and nothing else: a void, at the
 * highest frame rate this Mac can reach. The game keeps running underneath (the server ticks, chunks load, mobs move),
 * only drawing stops. Not saved: every launch starts with it off, so nobody restarts into a black screen.
 * MaxFpsModeMixin.
 */
public final class MaxFpsMode {
	private static volatile boolean on;

	private MaxFpsMode() {
	}

	public static boolean on() {
		return on;
	}

	public static void set(boolean value) {
		on = value;
	}
}
