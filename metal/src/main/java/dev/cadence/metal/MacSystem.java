package dev.cadence.metal;

/**
 * Cadence: macOS system signals (native/mcsys.m) for callers outside this package. Each call is cheap (a property read or
 * an atomic load); none throws: if the native side is missing the neutral value comes back.
 */
public final class MacSystem {
	public static final int QOS_USER_INTERACTIVE = 0x21, QOS_USER_INITIATED = 0x19, QOS_DEFAULT = 0x15, QOS_UTILITY = 0x11,
		QOS_BACKGROUND = 0x09;

	private MacSystem() {
	}

	/** 0 nominal, 1 fair (warming), 2 serious (throttling), 3 critical. */
	public static int thermalState() {
		try {
			return Native.thermalState();
		} catch (Throwable t) {
			return 0;
		}
	}

	/**
	 * How many fans this Mac has (0: fanless, such as a MacBook Air; -1: unknown). Read from the System Management Controller
	 * once; reading needs no privileges. Cadence never sets fan speed: macOS only allows that with administrator rights.
	 */
	public static int fanCount() {
		int n = fans;
		if (n == Integer.MIN_VALUE) {
			try {
				n = Native.fanCount();
			} catch (Throwable t) {
				n = -1;
			}
			fans = n;
		}
		return n;
	}

	private static volatile int fans = Integer.MIN_VALUE;

	/** The average speed of this Mac's fans in rpm, or -1 (no fans, or unreadable). */
	public static int fanRpm() {
		int n = fanCount();
		if (n <= 0) return -1;
		long sum = 0;
		int read = 0;
		for (int i = 0; i < n; i++) {
			int rpm;
			try {
				rpm = Native.fanRpm(i);
			} catch (Throwable t) {
				rpm = -1;
			}
			if (rpm >= 0) {
				sum += rpm;
				read++;
			}
		}
		return read == 0 ? -1 : (int) (sum / read);
	}

	/** The whole Mac's power draw in watts (Apple Silicon's SMC sensor), or -1 if unreadable. */
	public static double systemWatts() {
		try {
			int mw = Native.systemPowerMw();
			return mw < 0 ? -1 : mw / 1000.0;
		} catch (Throwable t) {
			return -1;
		}
	}

	// (the battery estimate: the Mac's draw averaged over the last ~half minute, refreshed at most every 2 s)
	private static double drawWatts = -1;
	private static long batteryAt;
	private static String batteryText = "";

	/**
	 * On battery: how long the battery lasts at the Mac's current draw ("about 3 h 10 min left"), from the energy left in it
	 * and the whole Mac's power use averaged over the last half minute; "" on AC power or when either can't be read. Cheap to
	 * ask every frame (worked out at most every 2 seconds).
	 */
	public static synchronized String batteryTimeLeft() {
		long now = System.nanoTime();
		if (now - batteryAt < 2_000_000_000L) return batteryText;
		batteryAt = now;
		if (!onBattery()) {
			drawWatts = -1;
			return batteryText = "";
		}
		double w = systemWatts(), wh;
		try {
			wh = Native.batteryWh();
		} catch (Throwable t) {
			wh = -1;
		}
		if (w > 0) drawWatts = drawWatts < 0 ? w : drawWatts + (w - drawWatts) * 0.07;
		if (wh <= 0 || drawWatts <= 0.5) return batteryText = "";
		long minutes = Math.round(wh / drawWatts * 60);
		return batteryText = minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
	}

	/** The energy left in the battery (watt-hours), -1 without one. */
	public static double batteryWh() {
		try {
			return Native.batteryWh();
		} catch (Throwable t) {
			return -1;
		}
	}

	/** Whether the Mac is running on its battery now (false on AC power, without a battery, or if it can't be read). */
	public static boolean onBattery() {
		try {
			return Native.onBattery() != 0;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Cadence (FramePacing): each presented frame stays on screen at least this long (0: as soon as possible). Set from the
	 * frame cap: with one, frames advance one per interval instead of jittering against the display's refreshes.
	 */
	public static void presentMinimum(double seconds) {
		try {
			Native.presentMinimum(seconds);
		} catch (Throwable t) {
			// (before the Metal backend is up, or without it: nothing to pace)
		}
	}

	/** Measurement: start counting how evenly frames reach the screen (presentedTime intervals). */
	public static void presentStatsStart() {
		try {
			Native.presentStatsOn(1);
		} catch (Throwable t) {
			// (no Metal backend)
		}
	}

	/** {intervals counted, of which longer than 1.5 refreshes} since presentStatsStart. */
	public static long[] presentStats() {
		try {
			return new long[] {Native.presentStatsTotal(), Native.presentStatsLong()};
		} catch (Throwable t) {
			return new long[] {0, 0};
		}
	}

	public static boolean lowPowerMode() {
		try {
			return Native.lowPower() != 0;
		} catch (Throwable t) {
			return false;
		}
	}

	/** 0 normal, 1 warning, 2 critical (macOS memory pressure). */
	public static int memoryPressure() {
		try {
			return Native.memoryPressure();
		} catch (Throwable t) {
			return 0;
		}
	}

	/** How many memory-pressure changes macOS has reported. */
	public static long memoryPressureEvents() {
		try {
			return Native.memoryPressureEvents();
		} catch (Throwable t) {
			return 0;
		}
	}

	/** Sets the calling thread's QoS class; true if macOS accepted it. */
	public static boolean setQos(int qos) {
		try {
			return Native.setQos(qos) == 0;
		} catch (Throwable t) {
			return false;
		}
	}

	public static int qos() {
		try {
			return Native.qosSelf();
		} catch (Throwable t) {
			return 0;
		}
	}

	/** The refresh rate in Hz of the screen the game is on (60 if unknown). */
	public static int displayHz() {
		try {
			return Native.displayHz();
		} catch (Throwable t) {
			return 60;
		}
	}

	/** The backing scale of the screen the game is on: 2.0 on a Retina display, 1.0 on most external monitors. */
	public static double displayScale() {
		try {
			int s = Native.displayScale();
			return s > 0 ? s / 100.0 : 2.0;
		} catch (Throwable t) {
			return 2.0;
		}
	}

	// ---- Cadence Quick Setup: what this Mac is ----

	/** A sysctl string ("machdep.cpu.brand_string" = "Apple M2"), "" if unknown. */
	public static String sysString(String name) {
		try (var arena = java.lang.foreign.Arena.ofConfined()) {
			var out = arena.allocate(256);
			int n = Native.sysString(arena.allocateFrom(name), out, 256);
			return n > 0 ? out.getString(0) : "";
		} catch (Throwable t) {
			return "";
		}
	}

	/** A sysctl number ("hw.memsize"), -1 if unknown. */
	public static long sysLong(String name) {
		try (var arena = java.lang.foreign.Arena.ofConfined()) {
			return Native.sysLong(arena.allocateFrom(name));
		} catch (Throwable t) {
			return -1;
		}
	}

	/** "MacBook Air (M2, 2022)" (the device tree's product name), "" if unknown. */
	public static String productName() {
		try (var arena = java.lang.foreign.Arena.ofConfined()) {
			var out = arena.allocate(256);
			return Native.productName(out, 256) > 0 ? out.getString(0) : "";
		} catch (Throwable t) {
			return "";
		}
	}

	/** The GPU's core count, -1 if unknown. */
	public static int gpuCores() {
		try {
			return Native.gpuCores();
		} catch (Throwable t) {
			return -1;
		}
	}

	/**
	 * Quick Setup's GPU test (native/mcbench.m, ~3 s, its own device and queue): {ms per frame of a fixed terrain-like scene,
	 * ms of a fixed shader-pack-like full-screen chain}, each at 1920x1080 and the median of three runs; null if it failed.
	 */
	public static double[] benchGpu() {
		try (var arena = java.lang.foreign.Arena.ofConfined()) {
			var out = arena.allocate(16);
			if (Native.benchGpu(out) == 0) return null;
			return new double[] {out.getAtIndex(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 0), out.getAtIndex(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 1)};
		} catch (Throwable t) {
			return null;
		}
	}
}
