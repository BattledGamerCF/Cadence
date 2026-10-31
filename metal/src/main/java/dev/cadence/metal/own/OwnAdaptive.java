package dev.cadence.metal.own;

/**
 * Cadence: the own renderer's occlusion step (uocc: a depth pyramid and a test of the units against it, between two halves of
 * the opaque pass) chosen per scene by measuring. It splits the main render pass, and each split stores and reloads every
 * attachment of the whole screen, so it only pays where much is hidden. Measured on the M2 Air at 2940x1686 (in-run probe):
 * ocean shore 2.13 -> 1.89 ms without it, over land 2.9 -> 2.4, high up looking down 4.8 -> 4.5, but underground 2.15 ->
 * 2.45 (it saves more than it costs there). Every HOLD seconds both modes are timed in alternating short windows for about a
 * second (the frame interval; under a frame cap both look alike and the choice stays) and the faster is kept;
 * the other must win by MARGIN to replace the current one. -Dcadence.own.adaptiveUocc=false: always on, as before.
 * Render thread.
 */
final class OwnAdaptive {
	static final boolean ON = !"false".equals(System.getProperty("cadence.own.adaptiveUocc"));
	private static final long WINDOW_NS = 120_000_000L, HOLD_NS = Long.getLong("cadence.own.adaptiveHoldMs", 8000) * 1_000_000L;
	private static final int WINDOWS_PER_MODE = 4;
	private static final double MARGIN = 0.03;

	private static boolean choice = true, trial = true;
	private static boolean exploring;
	private static long windowStart, holdUntil;
	private static int windowsDone, framesInWindow;
	private static final double[] sum = new double[2];
	private static final int[] frames = new int[2];
	private static int decisions, switches;

	private OwnAdaptive() {}

	/** This frame's choice: true = with the occlusion step. */
	static boolean uocc() {
		return !ON || (exploring ? trial : choice);
	}

	/** Once a frame, before the opaque terrain is drawn. */
	static void frame() {
		if (!ON) return;
		long now = System.nanoTime();
		if (!exploring) {
			if (now < holdUntil) return;
			exploring = true;
			sum[0] = sum[1] = 0;
			frames[0] = frames[1] = 0;
			windowsDone = 0;
			trial = !choice;  // the challenger first
			startWindow(now);
			return;
		}
		framesInWindow++;
		if (now - windowStart < WINDOW_NS) return;
		// the window's cost per frame: its frame interval, as the in-run probe measures (GPU busy time retires frames late and
		// misattributed them across these short windows). Under a frame cap both modes look alike and the choice stays.
		double perFrame = (now - windowStart) / 1e6 / Math.max(1, framesInWindow);
		if (framesInWindow < 4) {  // a few frames a second (minimised, a background cap, a long hitch): no verdict, try again later
			exploring = false;
			holdUntil = now + HOLD_NS;
			return;
		}
		int m = trial ? 1 : 0;
		sum[m] += perFrame;
		frames[m]++;
		windowsDone++;
		if (windowsDone >= 2 * WINDOWS_PER_MODE) {
			double on = sum[1] / Math.max(1, frames[1]), off = sum[0] / Math.max(1, frames[0]);
			boolean before = choice;
			if (choice && off < on * (1 - MARGIN)) choice = false;
			else if (!choice && on < off * (1 - MARGIN)) choice = true;
			decisions++;
			if (before != choice) switches++;
			if (dev.cadence.metal.backend.FrameHints.LOG_STATS && (before != choice || decisions % 20 == 1))
				System.out.printf("cadence-own adaptive occlusion: with %.3f ms, without %.3f ms -> %s (%d decisions, %d switches)%n",
					on, off, choice ? "with" : "without", decisions, switches);
			exploring = false;
			holdUntil = now + HOLD_NS;
			return;
		}
		trial = !trial;
		startWindow(now);
	}

	private static void startWindow(long now) {
		windowStart = now;
		framesInWindow = 0;
	}
}
