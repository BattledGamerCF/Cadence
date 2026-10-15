package dev.cadence.shaders.cadence.system;

import dev.cadence.metal.MacSystem;

/**
 * Cadence: tells macOS how urgent each of the game's threads is (QoS classes). Apple Silicon has fast (performance) and
 * efficient cores, and macOS uses a thread's QoS to choose between them and to order contended threads: the render thread
 * is marked user-interactive (it decides frame times), the integrated server user-initiated, and chunk-building workers
 * as -Dcadence.workerQos says (default: left as Java starts them; options: interactive, initiated, utility). Nothing in
 * the game's work changes, only the scheduling hints. -Dcadence.threadRoles=false: none set.
 */
public final class ThreadRoles {
	private static final boolean ENABLED = !"false".equals(System.getProperty("cadence.threadRoles"));
	private static final int WORKER = switch (System.getProperty("cadence.workerQos", "").toLowerCase(java.util.Locale.ROOT)) {
		case "interactive" -> MacSystem.QOS_USER_INTERACTIVE;
		case "initiated" -> MacSystem.QOS_USER_INITIATED;
		case "utility" -> MacSystem.QOS_UTILITY;
		case "background" -> MacSystem.QOS_BACKGROUND;
		default -> 0;
	};
	private static final ThreadLocal<Boolean> SET = new ThreadLocal<>();

	private ThreadRoles() {
	}

	public static void renderThread() {
		if (ENABLED) MacSystem.setQos(MacSystem.QOS_USER_INTERACTIVE);
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
