package dev.cadence.metal;

/**
 * Cadence: total time the GPU was busy with retired command buffers (the union of their GPUStartTime..GPUEndTime spans),
 * always on (two native calls per command buffer). Readers sample it twice and divide the difference by their frame count: GPU time per frame, which,
 * unlike the frame interval, still shows headroom under VSync or a frame cap. Written on the render thread only.
 */
public final class GpuBusy {
	private static volatile long totalNanos;
	private static double busyUntil;

	private GpuBusy() {}

	/** Command buffers overlap on the GPU (pipelined frames, several per frame): count the union of their spans, not the sum. */
	static void retired(long cmd) {
		double start = Native.cmdGpuStart(cmd), end = Native.cmdGpuEnd(cmd);
		if (!(start > 0 && end > start && end - start < 1)) return;
		double from = Math.max(start, busyUntil);
		if (end > from) totalNanos += Math.round((end - from) * 1e9);
		if (end > busyUntil) busyUntil = end;
	}

	/** Monotonic; 0 until the first command buffer retires. */
	public static long totalNanos() {
		return totalNanos;
	}
}
