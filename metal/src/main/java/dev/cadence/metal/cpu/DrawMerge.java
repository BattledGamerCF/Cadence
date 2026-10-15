// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.cpu;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

/** Scratch arrays and the merge for DrawMergeMixin (render thread only). */
public final class DrawMerge {
	/** The least the shared quad index buffer ever holds: 16384 quads (its first growth is to max(0, 1 + 16384) quads). */
	private static final long MAX_ELEMENTS = 16384L * 6;
	private static DrawMerge instance;
	private int capacity;
	public long pointers, counts, bases;
	private static long ranges, draws, lastPrint = System.nanoTime();

	private DrawMerge() {
	}

	public static DrawMerge scratch(int n) {
		DrawMerge m = instance;
		if (m == null) instance = m = new DrawMerge();
		if (m.capacity < n) {
			if (m.capacity > 0) {
				MemoryUtil.nmemFree(m.pointers);
				MemoryUtil.nmemFree(m.counts);
				MemoryUtil.nmemFree(m.bases);
			}
			m.capacity = Math.max(n, 256);
			m.pointers = MemoryUtil.nmemCalloc(m.capacity, Pointer.POINTER_SIZE);
			m.counts = MemoryUtil.nmemAlloc(m.capacity * 4L);
			m.bases = MemoryUtil.nmemAlloc(m.capacity * 4L);
		}
		return m;
	}

	/** Merges src (size n) into the scratch arrays; returns the merged count. */
	public int merge(long srcPointers, long srcCounts, long srcBases, int n) {
		int out = 0;
		long curPtr = MemoryUtil.memGetAddress(srcPointers);
		long curCount = MemoryUtil.memGetInt(srcCounts) & 0xFFFFFFFFL;
		long curBase = MemoryUtil.memGetInt(srcBases) & 0xFFFFFFFFL;
		for (int i = 1; i < n; i++) {
			long p = MemoryUtil.memGetAddress(srcPointers + (long) i * Pointer.POINTER_SIZE);
			long c = MemoryUtil.memGetInt(srcCounts + 4L * i) & 0xFFFFFFFFL;
			long b = MemoryUtil.memGetInt(srcBases + 4L * i) & 0xFFFFFFFFL;
			if (p == 0 && curPtr == 0 && curCount % 6 == 0 && b == curBase + curCount / 6 * 4 && curCount + c <= MAX_ELEMENTS) {
				curCount += c;
				continue;
			}
			put(out++, curPtr, curCount, curBase);
			curPtr = p;
			curCount = c;
			curBase = b;
		}
		put(out++, curPtr, curCount, curBase);
		if (Cpu.MERGE_STATS) stats(n, out);
		return out;
	}

	private void put(int i, long p, long c, long b) {
		MemoryUtil.memPutAddress(this.pointers + (long) i * Pointer.POINTER_SIZE, p);
		MemoryUtil.memPutInt(this.counts + 4L * i, (int) c);
		MemoryUtil.memPutInt(this.bases + 4L * i, (int) b);
	}

	private static void stats(int in, int out) {
		ranges += in;
		draws += out;
		long now = System.nanoTime();
		if (now - lastPrint > 5_000_000_000L) {
			lastPrint = now;
			System.out.printf("cadence-cpu: mergeDraws ranges %d -> draws %d (%.1f%%)%n", ranges, draws, 100.0 * draws / Math.max(1, ranges));
		}
	}
}
