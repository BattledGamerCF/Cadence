// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

/** The frame limiter's wait, on a timer macOS can't defer; see mc_sleep_precise and FramerateLimiterMixin. */
public final class FrameWait {
	/** -Dcadence.preciseLimiter=false keeps vanilla's park-then-spin wait. */
	public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("cadence.preciseLimiter", "true"));

	private FrameWait() {
	}

	public static void sleep(long nanos) {
		Native.sleepPrecise(nanos);
	}
}
