package dev.cadence.shaders.cadence.system;

import dev.cadence.metal.backend.MacSystem;

/**
 * Cadence: tells macOS how urgent each of the game's threads is (QoS classes). Apple Silicon has fast (performance) and
 * efficient cores, and macOS uses a thread's QoS to choose between them and to order contended threads: the render thread
 * is marked user-interactive (it decides frame times), the integrated server user-initiated, and chunk-building workers
 * as -Dcadence.workerQos says (options: interactive, initiated, utility, background). Default: left as Java starts them,
 * except on chips with a middle core tier (CoreLayout: the M6's 2 Super + 4 Performance + 6 Efficiency, the M5 Pro / Max's
 * Super + Performance), where workers are utility: macOS can't be told which core runs a thread, only how urgent it is, and
 * there the chunk building's bursts then settle on the Performance and Efficiency cores instead of competing for the few
 * Super cores with the render thread and the game logic. Nothing in the game's work changes, only the scheduling hints.
 * -Dcadence.threadRoles=false: none set.
 */
public final class ThreadRoles {
	private static final boolean ENABLED = !"false".equals(System.getProperty("cadence.threadRoles"));
	private static final int WORKER = switch (System.getProperty("cadence.workerQos", "").toLowerCase(java.util.Locale.ROOT)) {
		case "interactive" -> MacSystem.QOS_USER_INTERACTIVE;
		case "initiated" -> MacSystem.QOS_USER_INITIATED;
		case "utility" -> MacSystem.QOS_UTILITY;
		case "background" -> MacSystem.QOS_BACKGROUND;
		case "default" -> 0;
		default -> CoreLayout.hasMiddleTier() ? MacSystem.QOS_UTILITY : 0;
	};
	private static final ThreadLocal<Boolean> SET = new ThreadLocal<>();

	private ThreadRoles() {
	}

	public static void renderThread() {
		if (!ENABLED) return;
		MacSystem.setQos(MacSystem.QOS_USER_INTERACTIVE);
		org.slf4j.LoggerFactory.getLogger("cadence").info("CPU cores: {}; render thread interactive, game logic initiated, chunk workers {}",
			CoreLayout.describe(), switch (WORKER) {
				case MacSystem.QOS_USER_INTERACTIVE -> "interactive";
				case MacSystem.QOS_USER_INITIATED -> "initiated";
				case MacSystem.QOS_UTILITY -> "utility" + (CoreLayout.hasMiddleTier() ? " (middle core tier)" : "");
				case MacSystem.QOS_BACKGROUND -> "background";
				default -> "as Java starts them";
			});
	}

	public static void serverThread() {
		if (ENABLED) MacSystem.setQos(MacSystem.QOS_USER_INITIATED);
	}

	/** A chunk worker's task starts (cheap after the first time on each thread). */
	public static void worker() {
		if (!ENABLED || WORKER == 0 || SET.get() != null) return;
		SET.set(Boolean.TRUE);
		MacSystem.setQos(WORKER);
	}
}
