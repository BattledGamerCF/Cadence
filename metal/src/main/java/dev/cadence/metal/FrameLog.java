// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Measurement only (-Dcadence.own.int.frameLog=true): one row per submitted frame, written to framelog.csv in the working directory
 * when the game exits. Per frame: when it ended (epoch ms), its wall time (submit to submit), the render thread's wait time per site
 * (WaitStats' sites: in-flight limit, fence, nextDrawable, pacer, drain), time in timed regions of the render thread (client tick,
 * frame extract, frame render, vanilla's section scheduling, translucent resort scheduling, our terrain's per-frame event apply),
 * the render thread's CPU time and allocation, GC collections and their time, our terrain's events applied (publish, resort,
 * release, clear), and the submit index (GpuTimes' key, for the GPU span with -Dcadence.metal.gpuTimes). Render thread only; fixed
 * arrays, nothing allocated per frame.
 */
public final class FrameLog {
	public static final boolean ON = Boolean.getBoolean("cadence.own.int.frameLog");
	/** The per-frame counters run: for the CSV (ON) or for the stall recorder (Stalls). */
	public static final boolean COUNT = ON || Stalls.ON;
	public static final int TICK = 0, EXTRACT = 1, RENDER = 2, COMPILE = 3, RESORT_SCHED = 4, OWN_BEGIN = 5, TEXTURES = 6;
	private static final String[] REGIONS = {"tickUs", "extractUs", "renderUs", "compileUs", "resortSchedUs", "ownBeginUs", "texTickUs"};
	public static final int PUBLISH = 0, RESORT = 1, RELEASE = 2, CLEAR = 3;
	private static final String[] EVENTS = {"publish", "resort", "release", "clear"};
	private static final String[] WAITS = {"inflightUs", "fenceUs", "drawableUs", "paceUs", "drainUs"};
	/** Backend operations recorded this frame: texture writes and their pixels, buffer writes and their KB, buffer copies and KB, texture copies, render passes. */
	public static final int TEX_WRITES = 0, TEX_PX = 1, BUF_WRITES = 2, BUF_KB = 3, BUF_COPIES = 4, COPY_KB = 5, TEX_COPIES = 6, PASSES = 7, CMD_BUFS = 8, PRE_BUFS = 9, BLIT_ENCS = 10, PIPELINES = 11;
	private static final String[] OPS = {"texWrites", "texPx", "bufWrites", "bufKb", "bufCopies", "copyKb", "texCopies", "passes", "cmdBufs", "preBufs", "blitEncs", "pipelines"};
	private static final int CAP = 1 << 19;

	private static final long[] endMs = ON ? new long[CAP] : null;
	private static final int[] wallUs = ON ? new int[CAP] : null, cpuUs = ON ? new int[CAP] : null, allocKb = ON ? new int[CAP] : null;
	private static final int[] gcCount = ON ? new int[CAP] : null, gcMs = ON ? new int[CAP] : null, submit = ON ? new int[CAP] : null;
	private static final int[][] waitUs = ON ? new int[5][CAP] : null, regionUs = ON ? new int[7][CAP] : null, events = ON ? new int[4][CAP] : null;
	private static final int[][] ops = ON ? new int[12][CAP] : null;
	private static final long[] curOps = new long[12];

	private static final long[] curWait = new long[5], curRegion = new long[7], regionStart = new long[7];
	private static final int[] curEvents = new int[4];
	private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
	private static final List<GarbageCollectorMXBean> GCS = ManagementFactory.getGarbageCollectorMXBeans();
	private static int n;
	private static long lastNs, lastCpu = -1, lastAlloc = -1, lastGcCount = -1, lastGcMs;
	private static Thread renderThread;

	static {
		if (ON) {
			Runtime.getRuntime().addShutdownHook(new Thread(FrameLog::write, "cadence-framelog"));
			System.out.println("cadence-framelog: on (framelog.csv at exit)");
		}
	}

	private FrameLog() {
	}

	static void waited(int site, long ns) {
		curWait[site] += ns;
	}

	/** Start of a timed region on the render thread (nesting of the same region is not supported: the outer one counts). */
	public static void begin(int region) {
		if (regionStart[region] == 0) regionStart[region] = System.nanoTime();
	}

	public static void end(int region) {
		long s = regionStart[region];
		if (s == 0) return;
		curRegion[region] += System.nanoTime() - s;
		regionStart[region] = 0;
	}

	public static void event(int kind) {
		curEvents[kind]++;
	}

	static void op(int kind, long amount) {
		curOps[kind] += amount;
	}

	private static final java.util.Map<String, long[]> tickPasses = ON ? new java.util.TreeMap<>() : null;

	/** Inside the client tick or the texture manager's tick (their timers running). */
	static boolean inTick() {
		return regionStart[TICK] != 0 || regionStart[TEXTURES] != 0;
	}

	/** A render pass made during the client tick: counted by what it draws into (pass label, target, mip, size, render area). */
	static void tickPass(String key, long pixels) {
		long[] v = tickPasses.computeIfAbsent(key, k -> new long[2]);
		v[0]++;
		v[1] += pixels;
	}

	/** End of a submit (from WaitStats.frame). */
	static void frame(long submitIndex) {
		long now = System.nanoTime();
		if (renderThread == null) renderThread = Thread.currentThread();
		long cpu = THREADS.getCurrentThreadCpuTime();
		long alloc = THREADS instanceof com.sun.management.ThreadMXBean t ? t.getCurrentThreadAllocatedBytes() : 0;
		long gcc = 0, gct = 0;
		for (int i = 0, k = GCS.size(); i < k; i++) {
			GarbageCollectorMXBean g = GCS.get(i);
			gcc += Math.max(0, g.getCollectionCount());
			gct += Math.max(0, g.getCollectionTime());
		}
		if (lastNs != 0 && Stalls.ON) {
			Stalls.frame(now, now - lastNs, lastCpu < 0 ? 0 : cpu - lastCpu, lastAlloc < 0 ? 0 : alloc - lastAlloc, lastGcCount < 0 ? 0 : gcc - lastGcCount,
				lastGcCount < 0 ? 0 : gct - lastGcMs, curWait, curRegion, curOps);
		}
		if (ON && lastNs != 0 && n < CAP) {
			int i = n++;
			endMs[i] = System.currentTimeMillis();
			wallUs[i] = (int) Math.min(Integer.MAX_VALUE, (now - lastNs) / 1000);
			cpuUs[i] = lastCpu < 0 ? 0 : (int) ((cpu - lastCpu) / 1000);
			allocKb[i] = lastAlloc < 0 ? 0 : (int) ((alloc - lastAlloc) >> 10);
			gcCount[i] = lastGcCount < 0 ? 0 : (int) (gcc - lastGcCount);
			gcMs[i] = lastGcCount < 0 ? 0 : (int) (gct - lastGcMs);
			submit[i] = (int) submitIndex;
			for (int s = 0; s < 5; s++) waitUs[s][i] = (int) (curWait[s] / 1000);
			for (int r = 0; r < 7; r++) regionUs[r][i] = (int) (curRegion[r] / 1000);
			for (int e = 0; e < 4; e++) events[e][i] = curEvents[e];
			try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
				long c = stack.ncalloc(4, 3, 4);
				Native.encCounters(c);
				for (int k = 0; k < 3; k++) curOps[CMD_BUFS + k] += org.lwjgl.system.MemoryUtil.memGetInt(c + k * 4L);
			}
			for (int o = 0; o < 12; o++) ops[o][i] = (int) Math.min(Integer.MAX_VALUE, curOps[o]);
		}
		java.util.Arrays.fill(curWait, 0);
		java.util.Arrays.fill(curRegion, 0);
		java.util.Arrays.fill(curEvents, 0);
		java.util.Arrays.fill(curOps, 0);
		lastNs = now;
		lastCpu = cpu;
		lastAlloc = alloc;
		lastGcCount = gcc;
		lastGcMs = gct;
	}

	// the encoder log (-Dcadence.own.int.encLog): a ring of the last ENC_CAP encoders; per submit its origin (first sample, us since calibration)
	private static final int ENC_CAP = 1 << 20;
	private static final int[] encSubmit = ON ? new int[ENC_CAP] : null;
	private static final short[] encGroup = ON ? new short[ENC_CAP] : null, encLabel = ON ? new short[ENC_CAP] : null;
	private static final double[] encOrigin = ON ? new double[ENC_CAP] : null;
	private static final float[][] encT = ON ? new float[4][ENC_CAP] : null;
	private static final java.util.Map<String, Integer> LABELS = new java.util.HashMap<>();
	private static final List<String> LABEL_LIST = new java.util.ArrayList<>();
	private static long encRows;

	/** One profiled submit's encoders: 4 samples each (vertex start/end, fragment start/end; compute: start/end in the first two). */
	static void encoders(long ctx, long submit, long samples, int count, String[] labels) {
		if (!ON) return;
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			long out = stack.nmalloc(8, Math.max(1, count) * 8);
			Native.profileReadAbs(ctx, samples, count, out);
			double origin = Double.MAX_VALUE;
			for (int i = 0; i < count; i++) {
				double v = org.lwjgl.system.MemoryUtil.memGetDouble(out + i * 8L);
				if (v >= 0) origin = Math.min(origin, v);
			}
			if (origin == Double.MAX_VALUE) return;
			for (int g = 0; g < count / 4; g++) {
				int row = (int) (encRows++ % ENC_CAP);
				encSubmit[row] = (int) submit;
				encGroup[row] = (short) g;
				String label = g < labels.length && labels[g] != null ? labels[g] : "compute";
				encLabel[row] = (short) (int) LABELS.computeIfAbsent(label, k -> {
					LABEL_LIST.add(k);
					return LABEL_LIST.size() - 1;
				});
				encOrigin[row] = origin;
				for (int k = 0; k < 4; k++) {
					double v = org.lwjgl.system.MemoryUtil.memGetDouble(out + (g * 4L + k) * 8);
					encT[k][row] = v < 0 ? -1 : (float) (v - origin);
				}
			}
		}
	}

	private static void writeEncoders() {
		if (encRows == 0) return;
		Path out = Path.of(System.getProperty("user.dir"), "enclog.csv");
		try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.US_ASCII)) {
			w.write("submit,group,label,originUs,vsUs,veUs,fsUs,feUs\n");
			long first = Math.max(0, encRows - ENC_CAP);
			StringBuilder b = new StringBuilder(160);
			for (long r = first; r < encRows; r++) {
				int i = (int) (r % ENC_CAP);
				b.setLength(0);
				b.append(encSubmit[i]).append(',').append(encGroup[i]).append(',').append(LABEL_LIST.get(encLabel[i]).replace(',', ';')).append(',')
					.append(String.format("%.1f", encOrigin[i]));
				for (int k = 0; k < 4; k++) b.append(',').append(String.format("%.1f", encT[k][i]));
				w.write(b.append('\n').toString());
			}
		} catch (IOException e) {
			// measurement only
		}
	}

	private static void write() {
		writeEncoders();
		Path out = Path.of(System.getProperty("user.dir"), "framelog.csv");
		try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.US_ASCII)) {
			StringBuilder h = new StringBuilder("endMs,submit,wallUs,cpuUs,allocKb,gcCount,gcMs");
			for (String s : WAITS) h.append(',').append(s);
			for (String s : REGIONS) h.append(',').append(s);
			for (String s : EVENTS) h.append(',').append(s);
			for (String s : OPS) h.append(',').append(s);
			w.write(h.append('\n').toString());
			StringBuilder b = new StringBuilder(256);
			for (int i = 0; i < n; i++) {
				b.setLength(0);
				b.append(endMs[i]).append(',').append(submit[i]).append(',').append(wallUs[i]).append(',').append(cpuUs[i]).append(',').append(allocKb[i])
					.append(',').append(gcCount[i]).append(',').append(gcMs[i]);
				for (int s = 0; s < 5; s++) b.append(',').append(waitUs[s][i]);
				for (int r = 0; r < 7; r++) b.append(',').append(regionUs[r][i]);
				for (int e = 0; e < 4; e++) b.append(',').append(events[e][i]);
				for (int o = 0; o < 12; o++) b.append(',').append(ops[o][i]);
				w.write(b.append('\n').toString());
			}
		} catch (IOException e) {
			System.out.println("cadence-framelog: can't write " + out + ": " + e);
		}
		if (!tickPasses.isEmpty()) {
			// (to a file: the game's stdout is gone by the time shutdown hooks run)
			StringBuilder b = new StringBuilder("render passes made in the client tick (count, target pixels each, key):\n");
			tickPasses.forEach((k, v) -> b.append(v[0]).append("x ").append(v[1] / Math.max(1, v[0])).append(" px  ").append(k).append('\n'));
			try {
				Files.writeString(Path.of(System.getProperty("user.dir"), "framelog-passes.txt"), b, StandardCharsets.UTF_8);
			} catch (IOException e) {
				// measurement only
			}
		}
	}
}
