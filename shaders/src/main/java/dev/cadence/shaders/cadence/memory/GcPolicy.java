package dev.cadence.shaders.cadence.memory;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cadence's GC policy: when G1 does its background marking, decided by Cadence instead of G1's adaptive guess.
 *
 * <p>G1 frees young objects in short pauses, and finds dead old objects by marking the whole heap in the background; each
 * marking cycle ends in a Remark pause that also unloads classes and frees replaced JIT code. With Minecraft that Remark
 * takes 8-19 ms on an M2 (17 of them class unloading: "G1 Complete Cleaning" over every class and compiled method), and G1's
 * adaptive trigger (IHOP) had fallen to 0 bytes: the young generation takes so much of the committed heap that G1 marked
 * back to back, a Remark every ~8 s, while the old generation stayed flat at ~450 MB. Most of those cycles found nothing.
 *
 * <p>JvmDefaults starts Java with G1's trigger fixed at 90% of the heap (a safety net) and System.gc() starting a marking
 * cycle (ExplicitGCInvokesConcurrent). This then starts marking when there is something to find: after every collection it
 * reads the old generation; once that has grown by {@link #grow} since the lowest it was after the last cycle (garbage worth
 * reclaiming), it starts one. Half that growth is enough while a menu is open or the game is paused (a Remark there costs
 * nothing anyone sees), and memory pressure from macOS (MemoryGuard) lowers the bar to a quarter. Cycles are at least 30 s
 * apart. The JVM still starts its own cycle when replaced JIT code piles up (SweeperThreshold, 15% of the code cache), so
 * compiled code can't grow without bound.
 *
 * <p>On a Java whose G1 has the manageable G1ClassUnloadingInterval (the Vivace build of OpenJDK), class unloading is
 * scheduled too: Remark pauses that unload classes and compiled code cost 5-100 ms, those that don't ~1 ms. During play the
 * interval is raised to {@value #PLAY_INTERVAL} (the JVM still unloads at once when the code cache or metaspace needs it);
 * when a menu is open, at least one marking cycle has run since the last unloading request and a minute has passed, the
 * interval is set to 1 for one cycle Cadence starts, so that unloading happens while nothing moves.
 *
 * <p>Active only when those flags are in effect (G1, adaptive IHOP off, ExplicitGCInvokesConcurrent on); otherwise nothing
 * changes. -Dcadence.gcPolicy=false: G1's adaptive trigger and no Cadence decisions (also drop the flags with
 * cadence.jvmDefaults=false or your own Java arguments).
 */
public final class GcPolicy {
	private static final Logger LOGGER = LoggerFactory.getLogger("cadence");
	private static final long MIN_GAP_NS = 30_000_000_000L;
	private static volatile boolean active;
	/** Old-generation growth that is worth a marking cycle (bytes): 5% of the maximum heap, 48 to 128 MB. */
	private static long grow;
	/** The lowest old-generation occupancy seen since the last cycle began (bytes after a collection); MAX until known. */
	private static long low = Long.MAX_VALUE;
	private static long lastCycleNs;
	private static volatile int pressure;
	private static volatile boolean requested, requestUnload;
	/** The JVM has the manageable G1ClassUnloadingInterval (Vivace); the interval during play. */
	private static boolean unloadControl;
	private static final int PLAY_INTERVAL = 12;
	private static final long UNLOAD_GAP_NS = 60_000_000_000L;
	/** Marking cycles seen since the last unloading request (G1's end-of-cycle notifications), and when that request was. */
	private static int cyclesSinceUnload;
	private static long lastUnloadNs;
	private static int started, sinceLog;
	private static Thread worker;

	private GcPolicy() {
	}

	public static boolean active() {
		return active;
	}

	/** At startup (ShaderLoaderMod): checks the flags and listens to G1's collections. */
	public static void start() {
		try {
			if ("false".equals(System.getProperty("cadence.gcPolicy"))) return;
			HotSpotDiagnosticMXBean hs = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
			boolean g1 = "true".equals(hs.getVMOption("UseG1GC").getValue());
			boolean explicit = "true".equals(hs.getVMOption("ExplicitGCInvokesConcurrent").getValue())
				&& !"true".equals(hs.getVMOption("DisableExplicitGC").getValue());
			boolean fixed = "false".equals(hs.getVMOption("G1UseAdaptiveIHOP").getValue());
			if (!g1 || !explicit || !fixed) {
				LOGGER.info("Cadence GC policy: off (G1 {}, System.gc starts marking {}, fixed trigger {}): G1 decides alone", g1, explicit, fixed);
				return;
			}
			long max = Runtime.getRuntime().maxMemory();
			grow = Math.max(48L << 20, Math.min(128L << 20, max / 20));
			for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans())
				if (gc instanceof NotificationEmitter emitter) emitter.addNotificationListener((n, h) -> {
					if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType()))
						afterCollection(GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData()));
				}, null, null);
			worker = new Thread(GcPolicy::work, "cadence-gc-policy");
			worker.setDaemon(true);
			worker.start();
			active = true;
			LOGGER.info("Cadence GC policy: on: marking when the old generation has grown {} MB (half in menus), G1's own trigger at 90%", grow >> 20);
			try {
				hs.getVMOption("G1ClassUnloadingInterval");
				hs.setVMOption("G1ClassUnloadingInterval", Integer.toString(PLAY_INTERVAL));
				unloadControl = true;
				LOGGER.info("Cadence GC policy: this Java schedules class unloading (G1ClassUnloadingInterval {} during play, unloading in menus)", PLAY_INTERVAL);
			} catch (IllegalArgumentException absent) {
				// (a JVM without the flag: G1 unloads in every cycle, as always)
			}
		} catch (RuntimeException | LinkageError e) {
			LOGGER.info("Cadence GC policy: off ({})", e.toString());
		}
	}

	/** MemoryGuard: macOS memory pressure (0 none, 1 warning, 2 critical): marking starts sooner, so old garbage goes back. */
	public static void pressure(int level) {
		pressure = level;
	}

	/** After each collection (the JMX notification thread): the old generation's occupancy, and whether to start marking. */
	private static void afterCollection(GarbageCollectionNotificationInfo info) {
		MemoryUsage old = info.getGcInfo().getMemoryUsageAfterGc().get("G1 Old Gen");
		if (old == null) return;
		long used = old.getUsed();
		String name = info.getGcName();
		long now = System.nanoTime();
		synchronized (GcPolicy.class) {
			if (name.contains("Concurrent")) cyclesSinceUnload++;
			// class unloading while a menu is open (a JVM with G1ClassUnloadingInterval): one cycle with interval 1
			if (unloadControl && !requested && cyclesSinceUnload >= 2 && now - lastUnloadNs > UNLOAD_GAP_NS && quiet()) {
				cyclesSinceUnload = 0;
				lastUnloadNs = lastCycleNs = now;
				low = Long.MAX_VALUE;
				requestUnload = requested = true;
				GcPolicy.class.notifyAll();
				LOGGER.info("Cadence GC policy: unloading classes and code while a menu is open");
				return;
			}
			// a cycle G1 or the JVM started itself (its safety net, the code cache): a fresh start for the growth measure
			if (name.contains("Concurrent") && now - lastCycleNs > MIN_GAP_NS) {
				lastCycleNs = now;
				low = used;
			}
			low = Math.min(low, used);
			if (low == Long.MAX_VALUE || now - lastCycleNs < MIN_GAP_NS) return;
			long grown = used - low;
			long bar = pressure >= 1 ? grow / 4 : quiet() ? grow / 2 : grow;
			if (grown < bar) return;
			lastCycleNs = now;
			low = Long.MAX_VALUE;
			requested = true;
			GcPolicy.class.notifyAll();
			if (started < 20 || ++sinceLog >= 20) {
				sinceLog = 0;
				LOGGER.info("Cadence GC policy: marking (old generation {} MB, +{} MB{})", used >> 20, grown >> 20,
					pressure >= 1 ? ", memory pressure" : quiet() ? ", menu open" : "");
			}
			started++;
		}
	}

	/** A menu is open or the game is paused: a pause here goes unseen. (Read from another thread: a plain field read.) */
	private static boolean quiet() {
		try {
			var mc = net.minecraft.client.Minecraft.getInstance();
			return mc != null && (mc.isPaused() || mc.gui.screen() != null);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** System.gc() on its own thread: with ExplicitGCInvokesConcurrent it starts a marking cycle and waits for it here. */
	private static void work() {
		while (true) {
			synchronized (GcPolicy.class) {
				while (!requested) {
					try {
						GcPolicy.class.wait();
					} catch (InterruptedException e) {
						return;
					}
				}
				requested = false;
			}
			boolean unload;
			synchronized (GcPolicy.class) {
				unload = requestUnload;
				requestUnload = false;
			}
			if (unload) setInterval(1);
			try {
				System.gc();
			} finally {
				if (unload) setInterval(PLAY_INTERVAL);
			}
		}
	}

	private static void setInterval(int n) {
		try {
			ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).setVMOption("G1ClassUnloadingInterval", Integer.toString(n));
		} catch (RuntimeException e) {
			unloadControl = false;
		}
	}

	/** Self-test and logs: cycles Cadence started. */
	public static int started() {
		return started;
	}
}
