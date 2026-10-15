package dev.cadence.shaders.cadence.system;

/** Cadence: polls macOS's heat and memory signals every 2 seconds for HeatControl and MemoryGuard (one daemon thread). */
public final class SystemMonitor {
	private static boolean started;

	private SystemMonitor() {
	}

	public static synchronized void start() {
		if (started) return;
		started = true;
		Thread t = new Thread(() -> {
			dev.cadence.metal.MacSystem.setQos(dev.cadence.metal.MacSystem.QOS_UTILITY);
			while (true) {
				try {
					HeatControl.poll(System.nanoTime());
					MemoryGuard.poll();
				} catch (Throwable e) {
					// a missing native function: keep the game going without the governors
				}
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "cadence-system-monitor");
		t.setDaemon(true);
		t.start();
	}
}
