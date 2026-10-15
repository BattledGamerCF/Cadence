// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

/** Physical reservation only. Logical allocation/growth remains unchanged. */
final class OwnArenaSizing {
	static final int LOGICAL_INITIAL_UNITS = 1 << 20;

	private OwnArenaSizing() {
	}

	/** renderDistance is the configured option, not the server's transient join-time limit. */
	static int reserveUnits(boolean enabled, int renderDistance, boolean compact) {
		// Only RD16 compact terrain has a measured full-route steady capacity.
		// Other distances/layouts keep their original capacity; do not extrapolate memory.
		return enabled && compact && renderDistance == 16 ? 4 << 20 : LOGICAL_INITIAL_UNITS;
	}

	/** Unknown GPU topology fails closed: the reservation has only been accepted on the small-core Neo tier. */
	static boolean preSizeEnabled(boolean requested, int gpuCores, int maxGpuCores) {
		return requested && gpuCores >= 0 && maxGpuCores > 0 && gpuCores < maxGpuCores;
	}
}
