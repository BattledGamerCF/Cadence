// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

/** Rare growth events only; timestamps share the bench's System.nanoTime clock. */
final class OwnGrowth {
	private static final boolean ON = Boolean.getBoolean("cadence.own.growthStats");

	private OwnGrowth() {
	}

	static long begin() {
		return ON ? System.nanoTime() : 0;
	}

	static void end(String kind, long start, long oldBytes, long newBytes, long physicalBytes) {
		if (!ON) return;
		long end = System.nanoTime();
		// Format once before println: printf's fragmented writes can interleave with another thread's log event.
		System.out.println(String.format(java.util.Locale.ROOT,
			"cadence-own growth: kind=%s startNs=%d endNs=%d epochMs=%d oldBytes=%d newBytes=%d physicalBytes=%d durationUs=%.3f thread=%s",
			kind, start, end, System.currentTimeMillis(), oldBytes, newBytes, physicalBytes, (end - start) / 1e3, Thread.currentThread().getName()));
	}
}
