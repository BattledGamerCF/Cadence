// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.backend;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in, render-thread-only cadence recorder. No Java allocation on submit/present/retire.
 * Fixed capacity never wraps: overflow is reported rather than silently losing early phases.
 * Native presented callbacks write separate atomic slots and never call Java.
 */
public final class GpuTimes {
	private static final int CAPACITY = 1 << 20;
	private static final long[] submitNs = new long[CAPACITY], startNs = new long[CAPACITY], endNs = new long[CAPACITY];
	private static final boolean[] presenting = new boolean[CAPACITY];
	/** Render encoders the frame opened (vanilla's texture-animation tick frames open one per atlas mip). */
	private static final int[] passes = new int[CAPACITY];
	private static final long[] pollNs = Latency.ON ? new long[CAPACITY] : null;
	private static final long[] calibration = calibrate();
	private static int retired;
	private static long dropped;

	private GpuTimes() {}
	static void initialize() {} // initialize storage and clock mapping before recording

	/** Bracket the native clock with nanoTime, retaining the narrowest of 64 brackets.
	 * [Java midpoint - native ns, bracket width, Java midpoint]. Snapshot repeats this
	 * so reports can test both offset and drift instead of assuming identical origins.
	 */
	private static long[] calibrate() {
		long best = Long.MAX_VALUE, offset = 0, midpoint = 0;
		for (int i = 0; i < 64; i++) {
			long a = System.nanoTime();
			long host = Math.round(Native.hostSeconds() * 1e9);
			long b = System.nanoTime();
			if (b - a < best) { best = b - a; midpoint = a + best / 2; offset = midpoint - host; }
		}
		return new long[] {offset, best, midpoint};
	}

	static void submit(long index, int renderPasses) {
		if (index >= CAPACITY) return;
		submitNs[(int) index] = System.nanoTime();
		passes[(int) index] = renderPasses;
		if (probeNames != null) {
			probeModeOf[(int) index] = probeMode;
			probeWindowOf[(int) index] = probeWindow;
		}
	}

	/** The in-run probe (OwnTerrain, -Dcadence.own.probe): its current mode and window, tagged on each submit from now on. */
	private static volatile int probeMode = -1, probeWindow = -1;
	private static volatile String[] probeNames;
	private static int[] probeModeOf, probeWindowOf;

	public static void probeTag(int mode, int window, String[] names) {
		if (probeNames == null) {
			probeModeOf = new int[CAPACITY];
			probeWindowOf = new int[CAPACITY];
			java.util.Arrays.fill(probeModeOf, -1);
			java.util.Arrays.fill(probeWindowOf, -1);
		}
		probeMode = mode;
		probeWindow = window;
		probeNames = names;
	}
	static void present(long index, long drawable) {
		if (index >= CAPACITY) return;
		presenting[(int) index] = true;
		if (pollNs != null) pollNs[(int) index] = Latency.lastPollNs;
		Native.cadencePresent(drawable, (int) index);
	}
	/** A present whose drawable is acquired on the present side (presentQueue=acquire): native registers the scanout probe. */
	static void presentQueued(long index) {
		if (index >= CAPACITY) return;
		presenting[(int) index] = true;
		if (pollNs != null) pollNs[(int) index] = Latency.lastPollNs;
	}
	static void retire(long index, long cmd) {
		if (index >= CAPACITY) { dropped++; return; }
		int i = (int) index;
		double start = Native.cmdGpuStart(cmd), end = Native.cmdGpuEnd(cmd);
		startNs[i] = start > 0 ? Math.round(start * 1e9) + calibration[0] : 0;
		endNs[i] = end > 0 ? Math.round(end * 1e9) + calibration[0] : 0;
		retired = i + 1;
	}

	/** Called reflectively by the bench only at report time, not at phase boundaries.
	 * Phase membership uses GPU completion time. All submits are exported separately
	 * to permit cross-boundary/GC analysis; CPU frame membership remains unchanged.
	 */
	public static Map<String, Object> snapshot(Map<String, Map<String, Object>> phases) {
		Map<String, Object> out = new LinkedHashMap<>();
		// A warm report is its own index space: retain only this request, never relabel/export earlier requests.
		// Native callback slots remain global (not safe to recycle with outstanding command buffers).
		int first = 0;
		if (Boolean.getBoolean("cadence.bench.warm")) {
			long begin = Long.MAX_VALUE;
			for (Map<String, Object> p : phases.values()) {
				if (p.get("nanoRange") instanceof long[] r) begin = Math.min(begin, r[0]);
			}
			while (first < retired && endNs[first] < begin) first++;
			out.put("sessionSubmitOffset", first);
			out.put("sessionRetired", retired);
		}
		out.put("signal", "command-buffer GPU completion; handler timestamps are callback delivery, not scanout");
		out.put("clockCalibrationStart", calibration);
		out.put("clockCalibrationEnd", calibrate());
		out.put("capacity", CAPACITY);
		out.put("dropped", dropped);
		out.put("inFlight", Integer.getInteger("cadence.metal.inFlight", 2));
		out.put("submitIndex", java.util.stream.LongStream.range(0, retired - first).toArray());
		out.put("submitNs", Arrays.copyOfRange(submitNs, first, retired));
		out.put("gpuStartNs", Arrays.copyOfRange(startNs, first, retired));
		out.put("gpuEndNs", Arrays.copyOfRange(endNs, first, retired));
		out.put("presenting", Arrays.copyOfRange(presenting, first, retired));
		if (probeNames != null) {
			out.put("probeModes", probeNames);
			out.put("probeMode", Arrays.copyOfRange(probeModeOf, first, retired));
			out.put("probeWindow", Arrays.copyOfRange(probeWindowOf, first, retired));
		}
		out.put("renderPasses", Arrays.copyOfRange(passes, first, retired));
		if (pollNs != null) out.put("pollNs", Arrays.copyOfRange(pollNs, first, retired));
		long[] handlers = new long[retired], presented = new long[retired];
		for (int i = first; i < retired; i++) {
			long h = Native.cadenceHandler(i), p = Native.cadencePresented(i);
			handlers[i] = h == 0 ? 0 : h + calibration[0];
			presented[i] = p == 0 ? 0 : p + calibration[0];
		}
		out.put("presentedHandlerNs", Arrays.copyOfRange(handlers, first, retired));
		out.put("presentedTimeNs", Arrays.copyOfRange(presented, first, retired));
		for (Map<String, Object> phase : phases.values()) {
			long[] range = (long[]) phase.get("nanoRange");
			if (range == null) continue;
			int count = 0;
			for (int i = first; i < retired; i++)
				if (endNs[i] >= range[0] && endNs[i] < range[1]) count++;
			long[] indices = new long[count], submits = new long[count], starts = new long[count], allEnds = new long[count];
			int[] framePasses = new int[count];
			long[] callback = new long[count], scanout = new long[count], ends = new long[count], polls = new long[count];
			boolean[] presents = new boolean[count];
			int n = 0, row = 0;
			for (int i = first; i < retired; i++) {
				if (endNs[i] < range[0] || endNs[i] >= range[1]) continue;
				indices[row] = i - first;
				submits[row] = submitNs[i];
				starts[row] = startNs[i];
				allEnds[row] = endNs[i];
				framePasses[row] = passes[i];
				presents[row] = presenting[i];
				callback[row] = handlers[i];
				if (pollNs != null) polls[row] = pollNs[i];
				scanout[row++] = presented[i];
				if (presenting[i]) ends[n++] = endNs[i];
			}
			Map<String, Object> gpuFrames = new LinkedHashMap<>();
			gpuFrames.put("submitIndex", indices);
			gpuFrames.put("submitNs", submits);
			gpuFrames.put("gpuStartNs", starts);
			gpuFrames.put("gpuEndNs", allEnds);
			gpuFrames.put("renderPasses", framePasses);
			gpuFrames.put("presenting", presents);
			gpuFrames.put("presentedHandlerNs", callback);
			gpuFrames.put("presentedTimeNs", scanout);
			if (pollNs != null) gpuFrames.put("pollNs", polls);
			phase.put("gpuFrames", gpuFrames);
			phase.put("gpuEndNs", Arrays.copyOf(ends, n)); // presenting submits only
			phase.put("gpuPresentingFrames", n);
			putStats(phase, "gpu", Arrays.copyOf(ends, n));
			putStats(phase, "gpuAll", allEnds);

		}
		return out;
	}

	private static void putStats(Map<String, Object> phase, String prefix, long[] ends) {
		if (ends.length < 2) return;
		long[] intervals = new long[ends.length - 1];
		for (int i = 1; i < ends.length; i++) intervals[i - 1] = ends[i] - ends[i - 1];
		phase.put(prefix + "AvgFps", intervals.length * 1e9 / (ends[ends.length - 1] - ends[0]));
		Arrays.sort(intervals);
		phase.put(prefix + "Low1Fps", low(intervals, .01));
		phase.put(prefix + "Low01Fps", low(intervals, .001));
	}

	private static double low(long[] sorted, double fraction) {
		int n = Math.max(1, (int) (sorted.length * fraction));
		long sum = 0;
		for (int i = sorted.length - n; i < sorted.length; i++) sum += sorted[i];
		return n * 1e9 / sum;
	}
}
