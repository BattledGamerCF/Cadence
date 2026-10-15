// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Best-fit range allocator over [0, capacity) in units (quads, records): free ranges by start (for merging neighbours) and by
 * size then start (best fit). Not thread-safe: callers lock.
 */
final class OwnAlloc {
	private final TreeMap<Integer, Integer> byStart = new TreeMap<>();
	private final TreeSet<Long> bySize = new TreeSet<>();
	private int capacity;
	private long used;

	OwnAlloc(int capacity) {
		this.capacity = capacity;
		this.addFree(0, capacity);
	}

	int capacity() {
		return this.capacity;
	}

	long used() {
		return this.used;
	}

	/** Start of a free range of n units, or -1 when none is big enough. */
	int alloc(int n) {
		Long fit = this.bySize.ceiling((long) n << 32);
		if (fit == null) return -1;
		int start = (int) (long) fit, size = (int) (fit >>> 32);
		this.removeFree(start, size);
		if (size > n) this.addFree(start + n, size - n);
		this.used += n;
		return start;
	}

	void free(int start, int n) {
		if (n <= 0) return;
		this.used -= n;
		Map.Entry<Integer, Integer> before = this.byStart.floorEntry(start - 1);
		if (before != null && before.getKey() + before.getValue() == start) {
			this.removeFree(before.getKey(), before.getValue());
			start = before.getKey();
			n += before.getValue();
		}
		Integer after = this.byStart.get(start + n);
		if (after != null) {
			this.removeFree(start + n, after);
			n += after;
		}
		this.addFree(start, n);
	}

	/** Capacity grows to newCapacity; the new tail is free. */
	void grow(int newCapacity) {
		if (newCapacity <= this.capacity) return;
		int old = this.capacity;
		this.capacity = newCapacity;
		this.used += newCapacity - old;  // free() below takes it back out
		this.free(old, newCapacity - old);
	}

	private void addFree(int start, int size) {
		this.byStart.put(start, size);
		this.bySize.add((long) size << 32 | start);
	}

	private void removeFree(int start, int size) {
		this.byStart.remove(start);
		this.bySize.remove((long) size << 32 | start);
	}
}
