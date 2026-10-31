package dev.cadence.shaders.cadence.memory;

import java.lang.management.ManagementFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cadence: give unused Java heap back to the system when Minecraft was started with far more heap than it uses.
 *
 * <p>Launchers often set -Xmx4096m (CurseForge's default here) on an 8 GB Mac; G1 grows the committed heap toward it under
 * Minecraft's allocation rate and rarely shrinks it (it uncommits only when more than MaxHeapFreeRatio, 70%, is free after a
 * collection), while the shader pack's GPU memory competes for the same RAM. These are manageable JVM flags, set at runtime:
 * MaxHeapFreeRatio 30 / MinHeapFreeRatio 10 (G1 shrinks the heap after collections when much is free: what actually keeps
 * the heap small; 15 / 5 while macOS reports memory pressure), G1PeriodicGCInterval (a concurrent cycle when idle, so a quiet
 * game also shrinks), SoftMaxHeapSize (honoured by ZGC; G1 in JDK 25 ignores it, JDK-8236073). Nothing about rendering
 * changes. Applied when -Xmx is over 1.5 GB and RAM is 16 GB or less (G1 and ZGC; the G1-only flags are skipped under ZGC);
 * every 30 s SoftMaxHeapSize follows the heap that was live after the last collection (×1.6 + 128 MB, between 768 MB and
 * 75% of -Xmx), so a ZGC heap stays near what the game needs;
 * -Dcadence.heapTrim=false: leave the JVM as launched. Logs the heap every 10 minutes (every minute in self-tests). Also
 * returns LWJGL's freed native pages every 30 s (NativeTrim).
 */
public final class HeapTrim {
	private static final Logger LOGGER = LoggerFactory.getLogger("cadence");
	// every minute in self-test runs, every 10 minutes in play
	private static final long LOG_EVERY_MS = System.getProperty("cadence.selftest") != null ? 60_000 : 600_000;

	private HeapTrim() {
	}

	public static void apply() {
		long max = Runtime.getRuntime().maxMemory();
		long ram = ram();
		boolean apply = !"false".equals(System.getProperty("cadence.heapTrim")) && max > 1536L << 20 && ram > 0 && ram <= 16L << 30;
		applied = apply;
		maxHeap = max;
		if (apply) {
			// keep a comfortable working size: 1.5 GB, or a third of -Xmx if that is more (but G1 may still use all of -Xmx)
			long soft = Math.max(1536L << 20, max / 3);
			set("SoftMaxHeapSize", Long.toString(soft));
			set("MinHeapFreeRatio", "10");
			set("MaxHeapFreeRatio", "30");
			set("G1PeriodicGCInterval", "30000");
		}
		LOGGER.info("Cadence heap: max {} MB, RAM {} MB, trim {}", max >> 20, ram >> 20, apply ? "on" : "off");
		Thread t = new Thread(() -> {
			long nextLog = System.currentTimeMillis() + LOG_EVERY_MS;
			while (true) {
				try {
					Thread.sleep(30_000);
				} catch (InterruptedException e) {
					return;
				}
				if (apply) follow(max);
				if (ram > 0 && ram <= 16L << 30) NativeTrim.purge();
				if (System.currentTimeMillis() < nextLog) continue;
				nextLog += LOG_EVERY_MS;
				Runtime rt = Runtime.getRuntime();
				LOGGER.info("Cadence heap: used {} / committed {} / max {} MB, soft max {} MB", (rt.totalMemory() - rt.freeMemory()) >> 20,
					rt.totalMemory() >> 20, rt.maxMemory() >> 20, lastSoft >> 20);
			}
		}, "cadence-heap-log");
		t.setDaemon(true);
		t.start();
	}

	private static long lastSoft;
	private static volatile boolean tight, applied;
	private static volatile long maxHeap;

	/**
	 * Cadence (MemoryGuard): macOS memory pressure. Under pressure G1's free ratios tighten to 15 / 5 (it hands back more at its
	 * next shrink) and ZGC's soft limit hugs the live heap (×1.2 + 64 MB, from 512 MB); normal values return when it goes.
	 */
	public static void pressure(int level) {
		boolean was = tight;
		tight = level > 0;
		if (applied) {
			lastSoft = 0;
			follow(maxHeap);
			// (G1 shrinks by the free ratios; the order keeps Min <= Max at every step)
			if (tight && !was) {
				set("MinHeapFreeRatio", "5");
				set("MaxHeapFreeRatio", "15");
			} else if (!tight && was) {
				set("MaxHeapFreeRatio", "30");
				set("MinHeapFreeRatio", "10");
			}
		}
	}

	/** SoftMaxHeapSize from the heap live after the last collection (sum of the heap pools' collection usage). */
	private static void follow(long max) {
		long live = 0;
		for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() != java.lang.management.MemoryType.HEAP || pool.getCollectionUsage() == null) continue;
			live += pool.getCollectionUsage().getUsed();
		}
		if (live <= 0) return;
		long soft = tight ? Math.min((long) (max * 0.75), Math.max(512L << 20, (long) (live * 1.2) + (64L << 20)))
			: Math.min((long) (max * 0.75), Math.max(768L << 20, (long) (live * 1.6) + (128L << 20)));
		if (Math.abs(soft - lastSoft) < 32L << 20) return;
		lastSoft = soft;
		set("SoftMaxHeapSize", Long.toString(soft));
	}

	private static long ram() {
		try {
			return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
		} catch (RuntimeException | LinkageError e) {
			return -1;
		}
	}

	/** The player's own Java arguments: the options they set are theirs (as with Cadence's Java defaults). */
	private static final java.util.List<String> INPUT = ManagementFactory.getRuntimeMXBean().getInputArguments();

	private static void set(String flag, String value) {
		for (String a : INPUT) {
			if (a.startsWith("-XX:" + flag + "=")) return;
			if (flag.equals("SoftMaxHeapSize") && (a.startsWith("-XX:SoftMaxHeapSize"))) return;
		}
		try {
			ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).setVMOption(flag, value);
		} catch (RuntimeException e) {
			LOGGER.info("Cadence heap: {} not set: {}", flag, e.toString());
		}
	}
}
