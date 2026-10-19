package dev.cadence.shaders.cadence.system;

import dev.cadence.metal.MacSystem;

/**
 * Cadence: even frames under a frame cap. With a cap (Max Framerate, menus, In Background, On Battery, Cooling), each frame is
 * timed by the CPU's wait, which jitters against the display's refreshes: at 60 fps on a 60 Hz display a frame now and then
 * misses its refresh and the next one catches two, a visible hitch in a turning view. Here the display is asked to show each
 * frame at least the cap's interval (Metal's presentDrawable:afterMinimumDuration:, a hair under the interval so the cap itself
 * never drops), so a capped game advances one frame per interval; on ProMotion displays this also tells macOS the rate to
 * refresh at. Without a cap nothing changes. -Dcadence.framePacing=false: off.
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
		if (target == pacedFor) return;
		pacedFor = target;
		MacSystem.presentMinimum(target == 0 ? 0 : 1.0 / target * 0.97);
	}
}
