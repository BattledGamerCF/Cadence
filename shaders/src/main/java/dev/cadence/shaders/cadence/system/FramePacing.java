package dev.cadence.shaders.cadence.system;

import dev.cadence.metal.MacSystem;

/**
 * Cadence: even frames under a frame cap. With a cap (Max Framerate, menus, In Background, On Battery, Cooling), each frame is
 * timed by the CPU's wait, which jitters against the display's refreshes: at 60 fps on a 60 Hz display a frame now and then
 * misses its refresh and the next one catches two, a visible hitch in a turning view. Here the display is asked to show each
 * frame at least the cap's interval (Metal's presentDrawable:afterMinimumDuration:, a hair under the interval so the cap itself
 * never drops), so a capped game advances one frame per interval; on ProMotion displays this also tells macOS the rate to
 * refresh at. Without a cap nothing changes. -Dcadence.framePacing=false: off.
 *
 * <p>Only for caps a fixed-rate display can show evenly: a whole number of refreshes per frame (60, 30, 20 on a 60 Hz screen;
 * 120, 60, 40, 30 on 120 Hz). Any other cap (45, 50) would be rounded up to whole refreshes by the display (45 became 30 on a
 * MacBook Air); there the CPU's wait alone holds the cap on average.
 */
public final class FramePacing {
	private static final boolean ENABLED = !"false".equals(System.getProperty("cadence.framePacing"));
	/** The cap the display is currently paced for (0: none). */
	private static int pacedFor = -1;

	private FramePacing() {
	}

	/** FramerateCapMixin, each time the game reads its frame-rate limit (render thread): the effective cap. */
	public static void cap(int limit) {
		int target = ENABLED && limit > 0 && limit < net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF ? limit : 0;
		int hz = HeatControl.refreshHz();
		if (target > 0) {
			// refreshes per frame for this cap; not a whole number (within 3%): no pacing
			int n = Math.max(1, Math.round((float) hz / target));
			if (Math.abs((double) hz / n - target) > target * 0.03) target = 0;
		}
		if (target == pacedFor) return;
		pacedFor = target;
		MacSystem.presentMinimum(target == 0 ? 0 : 1.0 / target * 0.97);
		// a cap at a high-refresh display's own rate (120 on ProMotion): presents latched to its refreshes, one each. Unsynced, a
		// frame now and then landed in the refresh before or after its own, the display showing one frame twice and skipping the
		// next: an uneven 120 that reads as blur in motion
		dev.cadence.metal.MetalBridge.syncPresents(target > 60 && Math.abs(target - hz) <= hz * 0.03);
	}
}
