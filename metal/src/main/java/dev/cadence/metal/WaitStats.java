// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import java.util.HashMap;
import java.util.Map;

/**
 * Measurement only (-Dcadence.metal.waitStats): where the render thread blocks, once a second. Per wait site the time a frame spends
 * there, how often, and for frame retires which frame it waited for (lag = the submit being recorded minus the frame waited on);
 * fence waits by their caller outside the backend. Render thread only; nothing is allocated on the hot path unless a fence blocks.
 */
final class WaitStats {
	static final boolean PRINT = Boolean.getBoolean("cadence.metal.waitStats");
	/** The hooks run for the once-a-second print and for FrameLog's per-frame rows. */
	static final boolean ON = PRINT || FrameLog.ON;
	static final int INFLIGHT = 0, FENCE = 1, DRAWABLE = 2, PACE = 3, DRAIN = 4;
	private static final String[] NAMES = {"in-flight limit", "fence", "nextDrawable", "pacer", "drain"};
	private static final long[] nanos = new long[5], counts = new long[5];
	private static final long[][] lagNanos = new long[5][6];
	private static final Map<String, long[]> fenceCallers = new HashMap<>();
	private static long frames, windowStart, lastSubmit, wall;

	private WaitStats() {
	}

	static void wait(int site, long ns, long lag) {
		if (FrameLog.ON) FrameLog.waited(site, ns);
		nanos[site] += ns;
		counts[site]++;
		lagNanos[site][(int) Math.max(0, Math.min(5, lag))] += ns;
	}

	static void fenceCaller(long ns) {
		if (!PRINT) return;
		String caller = StackWalker.getInstance().walk(s -> s.map(f -> f.getClassName() + "." + f.getMethodName())
			.filter(n -> !n.startsWith("dev.cadence.metal.") && !n.startsWith("com.mojang.renderpearl.")).findFirst().orElse("?"));
		long[] v = fenceCallers.computeIfAbsent(caller, k -> new long[2]);
		v[0] += ns;
		v[1]++;
	}

	/** End of a submit (one frame); submitIndex is the submit just made. */
	static void frame(long submitIndex) {
		if (FrameLog.ON) FrameLog.frame(submitIndex);
		if (!PRINT) return;
		long now = System.nanoTime();
		if (lastSubmit != 0) wall += now - lastSubmit;
		lastSubmit = now;
		frames++;
		if (windowStart == 0) windowStart = now;
		if (now - windowStart < 1_000_000_000L || frames == 0) return;
		StringBuilder b = new StringBuilder(String.format("cadence-metal waits: %d frames, %.0f us/frame wall", frames, wall / 1e3 / frames));
		long waited = 0;
		for (int s = 0; s < 5; s++) {
			if (counts[s] == 0) continue;
			waited += nanos[s];
			b.append(String.format("; %s %.0f us/frame (%.0f%%, %.2f/frame", NAMES[s], nanos[s] / 1e3 / frames, 100.0 * nanos[s] / Math.max(1, wall),
				(double) counts[s] / frames));
			if (s == INFLIGHT || s == FENCE || s == DRAIN) {
				b.append(", by lag");
				for (int l = 0; l < 6; l++) if (lagNanos[s][l] > 0) b.append(String.format(" %d:%.0f", l, lagNanos[s][l] / 1e3 / frames));
			}
			b.append(')');
		}
		b.append(String.format("; rest %.0f us/frame", (wall - waited) / 1e3 / frames));
		if (!fenceCallers.isEmpty()) {
			b.append("; fence callers");
			fenceCallers.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(4)
				.forEach(e -> b.append(String.format(" %s %.0f us/frame x%.2f", e.getKey(), e.getValue()[0] / 1e3 / frames, (double) e.getValue()[1] / frames)));
		}
		System.out.println(b);
		java.util.Arrays.fill(nanos, 0);
		java.util.Arrays.fill(counts, 0);
		for (long[] l : lagNanos) java.util.Arrays.fill(l, 0);
		fenceCallers.clear();
		frames = 0;
		wall = 0;
		windowStart = now;
	}
}
