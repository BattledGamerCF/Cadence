// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

/**
 * -Dcadence.metal.latency=true (with -Dcadence.metal.gpuTimes=true): the time of the input poll that starts each frame
 * (RenderSystem.pollEvents: keyboard, mouse buttons and the mouse movement the frame's camera turn uses), stored with that frame's
 * presenting command buffer, so a report can give input-sample -> commit -> GPU done -> presented per shown frame.
 */
public final class Latency {
	public static final boolean ON = Boolean.getBoolean("cadence.metal.latency");
	public static volatile long lastPollNs;

	private Latency() {
	}
}
