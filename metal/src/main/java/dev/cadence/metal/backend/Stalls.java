package dev.cadence.metal.backend;

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
	/** When the watchdog samples a long frame, in ms into it. */
	private static final long[] SAMPLE_AT_MS = {0, 250, 750, 1500, 3000};
	private static final StackTraceElement[][] sampled = new StackTraceElement[SAMPLE_AT_MS.length][];
	private static final long[] sampledAtMs = new long[SAMPLE_AT_MS.length];
	private static volatile int samples;
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

	/**
	 * The watchdog: every 10 ms, a render thread still on the same frame past the threshold has its stack taken, and again at
	 * 250, 750, 1,500 and 3,000 ms past it while the frame lasts (a long stall can move on from where it began).
	 */
	private static void watch() {
		while (true) {
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				return;
			}
			long start = frameStart;
			Thread render = renderThread;
			if (start == 0 || render == null) continue;
			long past = (System.nanoTime() - start - thresholdNs()) / 1_000_000L;
			if (past < 0) continue;
			int n;
			synchronized (sampled) {
				if (sampledFor != start) {
					sampledFor = start;
					samples = 0;
				}
				n = samples;
				if (n >= SAMPLE_AT_MS.length || past < SAMPLE_AT_MS[n]) continue;
			}
			// (taken outside the lock: the render thread's frame() takes it too; kept only if that frame is still the one running)
			StackTraceElement[] stack = render.getStackTrace();
			synchronized (sampled) {
				if (frameStart != start || sampledFor != start || samples != n) continue;
				sampled[n] = stack;
				sampledAtMs[n] = past + thresholdNs() / 1_000_000L;
				samples = n + 1;
			}
		}
	}

	/**
	 * A frame counts as a stall past 50 ms, or past three times the usual frame (a frame cap: In Background's 15 fps makes every
	 * frame 67 ms; those aren't stalls).
	 */
	private static long thresholdNs() {
		return Math.max(THRESHOLD_NS, 3 * usualNs);
	}

	/** The usual frame's length (an average of the frames that weren't stalls), without the pacer's waits. */
	private static volatile long usualNs = 16_000_000L;
	private static int slowRun;

	private static boolean inWorld() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		// (in the background or minimized a frame can wait seconds for the window: not a stall)
		return mc != null && mc.level != null && !(mc.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)
			&& mc.getWindow() != null && mc.getWindow().isFocused();
	}

	/**
	 * End of a frame (FrameLog.frame, render thread): its wall time and the counters FrameLog gathered over it. Reports it when
	 * it was a stall.
	 */
	static void frame(long now, long wallNs, long cpuNs, long allocBytes, long gcCount, long gcMs, long[] waitNs, long[] regionNs, long[] ops) {
		if (renderThread == null) renderThread = Thread.currentThread();
		long libraryNs = MetalPipeline.LIBRARY_NS.getAndSet(0), stateNs = MetalPipeline.STATE_NS.getAndSet(0), made = MetalPipeline.MADE.getAndSet(0);
		long started = frameStart;
		boolean world = inWorld();
		frameStart = world ? now : 0;
		if (!world) {
			inWorldSince = 0;
			return;
		}
		if (inWorldSince == 0) inWorldSince = now;
		long busyNs = wallNs - waitNs[3];  // (the pacer's wait is a frame cap at work, not a stall)
		long threshold = thresholdNs();
		if (busyNs < threshold) {
			usualNs += (busyNs - usualNs) / 16;
			slowRun = 0;
			return;
		}
		// (frames this slow for a while, a heavy pack on a small Mac: the new usual, learnt quickly, not a stall each)
		if (++slowRun > 3) usualNs += (busyNs - usualNs) / 4;
		if (started == 0 || now - inWorldSince < SETTLE_NS) return;
		StackTraceElement[][] stacks;
		long[] stackMs;
		synchronized (sampled) {
			int n = sampledFor == started ? samples : 0;
			stacks = java.util.Arrays.copyOf(sampled, n);
			stackMs = java.util.Arrays.copyOf(sampledAtMs, n);
			sampledFor = 0;
		}
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
		if (made > 0) work.append(", ").append(made).append(String.format(Locale.ROOT, " pipelines made (shader libraries %.0f ms, pipeline states %.0f ms, over all threads; the last %s)", libraryNs / 1e6, stateNs / 1e6, MetalPipeline.LAST_MADE));
		if (ops[FrameLog.TEX_WRITES] > 0) work.append(String.format(Locale.ROOT, ", %d texture uploads (%.1f MP)", ops[FrameLog.TEX_WRITES], ops[FrameLog.TEX_PX] / 1e6));
		if (ops[FrameLog.BUF_KB] + ops[FrameLog.COPY_KB] > 1024) work.append(String.format(Locale.ROOT, ", %.1f MB to buffers", (ops[FrameLog.BUF_KB] + ops[FrameLog.COPY_KB]) / 1024.0));
		if (work.length() > 0) b.append(" | GPU work:").append(work.substring(1));
		for (int k = 0; k < stacks.length; k++) {
			StackTraceElement[] stack = stacks[k];
			if (stack == null) continue;
			b.append(" | render thread at ").append(stackMs[k]).append(" ms: ");
			int shown = 0;
			for (int i = 0; i < stack.length && shown < 6; i++) {
				StackTraceElement e = stack[i];
				String c = e.getClassName();
				// (the JDK's frames say little: the first game or mod frames do; the top frame is kept whatever it is)
				if (i > 0 && (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun."))) continue;
				if (shown++ > 0) b.append(" < ");
				b.append(shortName(e));
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
