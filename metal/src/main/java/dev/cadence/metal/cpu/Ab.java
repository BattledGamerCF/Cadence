// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.cpu;

/**
 * In-process A/B timer for small exact levers whose effect is below run-to-run noise: a timed region (once per frame) alternates
 * the lever off/on call by call within one run, and the mean time of the region per arm is printed every 5 s.
 */
public final class Ab {
	private final String name;
	private int calls;
	private boolean arm;
	private long t0, n0, n1, ns0, ns1, lastPrint = System.nanoTime();

	public Ab(String name) {
		this.name = name;
	}

	/** Frames rendered (bumped by AbFrameMixin when any A/B is on): arms alternate per frame, so regions called several times
	 * a frame (prepareFrame: level, then GUI) don't split by call kind. */
	public static long frame;

	/** Starts the region; returns whether the lever is on for this call (this frame). */
	public boolean begin() {
		calls++;
		arm = (frame & 1) == 1;
		t0 = System.nanoTime();
		return arm;
	}

	public void end() {
		long now = System.nanoTime(), d = now - t0;
		if (arm) {
			n1++;
			ns1 += d;
		} else {
			n0++;
			ns0 += d;
		}
		if (now - lastPrint > 5_000_000_000L && n0 > 0 && n1 > 0) {
			lastPrint = now;
			System.out.printf("cadence-cpu: ab %s off %.2f us (n %d) on %.2f us (n %d) ratio %.3f%n", name, ns0 / 1e3 / n0, n0, ns1 / 1e3 / n1, n1,
				(double) (ns1 / n1) / (ns0 / n0));
		}
	}
}
