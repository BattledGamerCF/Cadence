// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.backend;

/** Lets dev.cadence.metal.cpu.AbShots switch the texel-view cache per frame (it lives in this package). */
public final class TexelViewsSwitch {
	private TexelViewsSwitch() {
	}

	public static void set(boolean on) {
		TexelViews.active = on;
	}

	/** The native pass-begin lever (mc_cpu_flags), for AbShots. */
	public static void pass(boolean on) {
		Native.cpuFlags(dev.cadence.metal.cpu.Cpu.nativeFlags(on));
	}
}
