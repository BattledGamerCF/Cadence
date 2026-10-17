package dev.cadence.metal;

/**
 * Cadence (diagnostics): with -Dcadence.metal.profileSplit=true and -Dcadence.metal.trace=N, the profiled frames' open render
 * pass is split at each mark, so what follows it gets GPU timestamps of its own (the own terrain's draws set no pipeline).
 */
public final class GpuProfileMark {
	private GpuProfileMark() {}

	public static void mark(String label) {
		if (MetalEncoder.PROFILE_SPLIT) MetalEncoder.profileMark(label);
	}
}
