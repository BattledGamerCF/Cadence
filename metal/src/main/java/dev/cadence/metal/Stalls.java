package dev.cadence.metal;

import java.util.Locale;

/**
 * Cadence: the stall recorder (on by default; -Dcadence.stalls=false turns it off). A frame that takes longer than
 * -Dcadence.stalls.ms (50) in a world gets one line in the log saying where its time went: the render thread's CPU time and
 * allocation, its waits (the GPU's in-flight limit, a fence, the next drawable, the pacer), its timed regions (client tick, frame
 * extract, frame render, section scheduling), garbage collections, the backend's work (pipelines compiled, texture and buffer
 * uploads), and the render thread's stack as a watchdog thread saw it once the frame had run past the threshold, which names
 * the culprit when it's none of the above (a disk read, a lock, a mod). At most one line every 2 seconds (the ones in between
 * counted). Built on FrameLog's per-frame counters, which run while this is on; nothing is allocated per frame.
 */
public final class Stalls {
	public static final boolean ON = !"false".equals(System.getProperty("cadence.stalls"));
	private static final long THRESHOLD_NS = Math.max(16, Integer.getInteger("cadence.stalls.ms", 50)) * 1_000_000L;
	/** The first frames after joining a world (chunks arriving, pipelines first used) are not reported. */
	private static final long SETTLE_NS = 10_000_000_000L;
	private static final String[] WAITS = {"in-flight limit", "fence", "next drawable", "pacer", "drain"};
	private static final String[] REGIONS = {"client tick", "frame extract", "frame render", "section scheduling", "translucent sort scheduling", "terrain updates", "texture animation"};

	/** When the current frame began (the last frame's end), for the watchdog; 0 while not in a world. */
	private static volatile long frameStart;
	private static volatile Thread renderThread;
	private static volatile StackTraceElement[] sampled;
	private static volatile long sampledFor;
	private static long inWorldSince, lastReport;
	private static int suppressed;

	private Stalls() {
	}

	static {
		if (ON) {
			Thread t = new Thread(Stalls::watch, "cadence-stall-watchdog");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			t.start();
		}
	}

	/** The watchdog: every 10 ms, a render thread still on the same frame past the threshold has its stack taken (once a frame). */
	private static void watch() {
		while (true) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				return;
			}
			long start = frameStart;
			Thread render = renderThread;
			if (start == 0 || render == null || sampledFor == start) continue;
			if (System.nanoTime() - start < THRESHOLD_NS) continue;
			sampledFor = start;
			sampled = render.getStackTrace();
		}
	}

	private static boolean inWorld() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		return mc != null && mc.level != null && !(mc.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen);
	}

	/**
	 * End of a frame (FrameLog.frame, render thread): its wall time and the counters FrameLog gathered over it. Reports it when
	 * it was a stall.
	 */
	static void frame(long now, long wallNs, long cpuNs, long allocBytes, long gcCount, long gcMs, long[] waitNs, long[] regionNs, long[] ops) {
		if (renderThread == null) renderThread = Thread.currentThread();
		long started = frameStart;
		boolean world = inWorld();
		frameStart = world ? now : 0;
		if (!world) {
			inWorldSince = 0;
			return;
		}
		if (inWorldSince == 0) inWorldSince = now;
		if (wallNs < THRESHOLD_NS || started == 0 || now - inWorldSince < SETTLE_NS) return;
		StackTraceElement[] stack = sampledFor == started ? sampled : null;
		if (now - lastReport < 2_000_000_000L) {
			suppressed++;
			return;
		}
		lastReport = now;
		StringBuilder b = new StringBuilder(256);
		b.append(String.format(Locale.ROOT, "[cadence] stall: a %.0f ms frame", wallNs / 1e6));
		if (suppressed > 0) b.append(" (").append(suppressed).append(" more since the last line)");
		suppressed = 0;
		b.append(String.format(Locale.ROOT, " | render thread CPU %.0f ms, %.1f MB allocated", cpuNs / 1e6, allocBytes / 1048576.0));
		appendTop(b, " | waits: ", WAITS, waitNs, wallNs);
		appendTop(b, " | in: ", REGIONS, regionNs, wallNs);
		b.append(gcCount > 0 ? " | GC: " + gcCount + " (" + gcMs + " ms)" : " | GC: none");
		StringBuilder work = new StringBuilder();
		if (ops[FrameLog.PIPELINES] > 0) work.append(", ").append(ops[FrameLog.PIPELINES]).append(" pipelines made");
		if (ops[FrameLog.TEX_WRITES] > 0) work.append(String.format(Locale.ROOT, ", %d texture uploads (%.1f MP)", ops[FrameLog.TEX_WRITES], ops[FrameLog.TEX_PX] / 1e6));
		if (ops[FrameLog.BUF_KB] + ops[FrameLog.COPY_KB] > 1024) work.append(String.format(Locale.ROOT, ", %.1f MB to buffers", (ops[FrameLog.BUF_KB] + ops[FrameLog.COPY_KB]) / 1024.0));
		if (work.length() > 0) b.append(" | GPU work:").append(work.substring(1));
		if (stack != null) {
			b.append(" | render thread at ").append(THRESHOLD_NS / 1_000_000).append(" ms: ");
			int shown = 0;
			for (StackTraceElement e : stack) {
				String c = e.getClassName();
				// (the JDK's and the backend's frames say little: the first game or mod frames do)
				if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.")) {
					if (shown == 0 && stack.length > 0 && e == stack[0]) b.append(shortName(e)).append(" < ");
					continue;
				}
				b.append(shortName(e));
				if (++shown == 6) break;
				b.append(" < ");
			}
		}
		System.out.println(b);
	}

	/** The parts that took at least 2 ms, largest first. */
	private static void appendTop(StringBuilder b, String head, String[] names, long[] ns, long wallNs) {
		boolean any = false;
		boolean[] used = new boolean[ns.length];
		for (int k = 0; k < ns.length; k++) {
			int best = -1;
			for (int i = 0; i < ns.length; i++) if (!used[i] && ns[i] >= 2_000_000L && (best < 0 || ns[i] > ns[best])) best = i;
			if (best < 0) break;
			used[best] = true;
			b.append(any ? ", " : head).append(names[best]).append(String.format(Locale.ROOT, " %.0f ms", ns[best] / 1e6));
			any = true;
		}
	}

	private static String shortName(StackTraceElement e) {
		String c = e.getClassName();
		return c.substring(c.lastIndexOf('.') + 1) + "." + e.getMethodName();
	}
}
