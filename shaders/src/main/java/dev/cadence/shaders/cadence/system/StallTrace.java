package dev.cadence.shaders.cadence.system;

import dev.cadence.shaders.cadence.RenderScale;
import org.slf4j.LoggerFactory;

/**
 * Cadence (diagnostics, -Dcadence.stallTrace=MS): when a frame in a world takes longer than MS (default 200), the render thread's
 * stack is sampled every 25 ms while the stall lasts and the most frequent frames are logged, so a hitch can be traced to its
 * cause. Off unless asked for; costs nothing then.
 */
public final class StallTrace {
	private static final long LIMIT_MS = Long.getLong("cadence.stallTrace", 0);

	private StallTrace() {
	}

	public static void start() {
		if (LIMIT_MS <= 0) return;
		Thread t = new Thread(StallTrace::run, "cadence-stall-trace");
		t.setDaemon(true);
		t.start();
	}

	private static void run() {
		long lastCount = -1, sinceNs = System.nanoTime();
		java.util.Map<String, Integer> hits = new java.util.HashMap<>();
		int samples = 0;
		while (true) {
			try {
				Thread.sleep(25);
			} catch (InterruptedException e) {
				return;
			}
			long count = RenderScale.frameCount, now = System.nanoTime();
			if (count != lastCount) {
				if (samples > 0) report(hits, samples, (now - sinceNs) / 1_000_000);
				hits.clear();
				samples = 0;
				lastCount = count;
				sinceNs = now;
				continue;
			}
			if (count <= 0 || (now - sinceNs) / 1_000_000 < LIMIT_MS || loading()) {
				if (loading()) sinceNs = now;  // (world loading or saving: no world frames are drawn, nothing is stuck)
				continue;
			}
			Thread render = find(RenderScale.renderThreadId);
			if (render == null) continue;
			StackTraceElement[] st = render.getStackTrace();
			samples++;
			// the stack from the first frame that isn't a JDK internal, a few frames deep, as the key
			StringBuilder key = new StringBuilder();
			int n = 0;
			for (StackTraceElement e : st) {
				if (n == 0 && (e.getClassName().startsWith("java.") || e.getClassName().startsWith("jdk.") || e.getClassName().startsWith("sun."))) continue;
				key.append(n == 0 ? "" : " <- ").append(e.getClassName().replaceAll(".*\\.", "")).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
				if (++n == 8) break;
			}
			hits.merge(key.toString(), 1, Integer::sum);
		}
	}

	private static void report(java.util.Map<String, Integer> hits, int samples, long ms) {
		var log = LoggerFactory.getLogger("cadence");
		log.info("Stall: a frame took about {} ms ({} samples of the render thread)", ms, samples);
		hits.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3)
			.forEach(e -> log.info("  {}x {}", e.getValue(), e.getKey()));
	}

	private static boolean loading() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		var screen = mc == null || mc.gui == null ? null : mc.gui.screen();
		if (mc == null || mc.level == null) return true;
		if (screen == null) return false;
		String n = screen.getClass().getSimpleName();
		return n.contains("Loading") || n.contains("Progress") || n.contains("GenericMessage") || n.contains("Receiving");
	}

	private static Thread find(long id) {
		for (Thread t : Thread.getAllStackTraces().keySet()) if (t.threadId() == id) return t;
		return null;
	}
}
