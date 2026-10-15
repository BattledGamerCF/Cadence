// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.lang.management.ManagementFactory;

/**
 * -Dcadence.own.mesh.stats=true: per section compile (whichever mesher runs), wall time, thread CPU time and bytes allocated by
 * the compiling thread. Logged every 2 s for the window since the last line (t = seconds since class load): count, wall mean /
 * p50 / p99 ms, CPU mean / p99 ms, allocation mean KB per section.
 */
public final class OwnMeshStats {
	public static final boolean ON = Boolean.getBoolean("cadence.own.mesh.stats");
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static final int BINS = 4000;  // 10 us bins up to 40 ms
	private static final long[] wall = new long[BINS + 1], cpu = new long[BINS + 1];
	private static long count, wallNs, cpuNs, allocBytes, logAt, total;
	private static final long START = System.nanoTime();
	private static final ThreadLocal<long[]> T = ThreadLocal.withInitial(() -> new long[3]);
	/** Every compile (epoch ms, wall ns, cpu ns, bytes), written to ownmesh-compiles.csv in the game dir at exit (bench phases by epoch). */
	private static long[] records = new long[4 * 65536];
	private static int recorded;

	static {
		if (ON) Runtime.getRuntime().addShutdownHook(new Thread(OwnMeshStats::dump, "cadence-own mesh stats"));
	}

	private static synchronized void dump() {
		try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.FileWriter("ownmesh-compiles.csv")))) {
			out.println("epochMs,wallNs,cpuNs,allocBytes");
			for (int i = 0; i < recorded; i++) out.println(records[4 * i] + "," + records[4 * i + 1] + "," + records[4 * i + 2] + "," + records[4 * i + 3]);
		} catch (java.io.IOException e) {
			System.out.println("cadence-own mesh stats: " + e);
		}
	}

	private OwnMeshStats() {
	}

	public static void start() {
		long[] t = T.get();
		t[2] = THREADS.getCurrentThreadAllocatedBytes();
		t[1] = THREADS.getCurrentThreadCpuTime();
		t[0] = System.nanoTime();
	}

	public static void end() {
		long w = System.nanoTime();
		long c = THREADS.getCurrentThreadCpuTime(), a = THREADS.getCurrentThreadAllocatedBytes();
		long[] t = T.get();
		record(w - t[0], c - t[1], a - t[2]);
	}

	private static synchronized void record(long w, long c, long a) {
		long now = System.nanoTime();
		if (4 * recorded == records.length) records = java.util.Arrays.copyOf(records, records.length * 2);
		records[4 * recorded] = System.currentTimeMillis();
		records[4 * recorded + 1] = w;
		records[4 * recorded + 2] = c;
		records[4 * recorded + 3] = a;
		recorded++;
		count++;
		wallNs += w;
		cpuNs += c;
		allocBytes += a;
		wall[(int) Math.min(BINS, w / 10_000)]++;
		cpu[(int) Math.min(BINS, c / 10_000)]++;
		if (now > logAt) {
			logAt = now + 2_000_000_000L;
			// the window since the last line (pick the bench phase by t), then reset
			System.out.printf("cadence-own mesh stats: t %.1f s, %d compiles, wall %.3f ms mean, p50 %.2f, p99 %.2f; cpu %.3f ms mean, p99 %.2f; alloc %.1f KB/section; total %d%n",
				(now - START) / 1e9, count, wallNs / 1e6 / count, pct(wall, 0.5), pct(wall, 0.99), cpuNs / 1e6 / count, pct(cpu, 0.99),
				allocBytes / 1024.0 / count, total += count);
			count = wallNs = cpuNs = allocBytes = 0;
			java.util.Arrays.fill(wall, 0);
			java.util.Arrays.fill(cpu, 0);
		}
	}

	private static double pct(long[] h, double q) {
		long target = (long) Math.ceil(count * q), acc = 0;
		for (int i = 0; i <= BINS; i++) {
			acc += h[i];
			if (acc >= target) return (i + 0.5) * 0.01;
		}
		return BINS * 0.01;
	}

}
