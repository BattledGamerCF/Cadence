package dev.cadence.shaders.cadence;

import dev.cadence.shaders.cadence.mixin.SectionTaskAccessor;
import java.util.Arrays;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection.SectionTask;
import net.minecraft.world.phys.Vec3;

/**
 * Cadence: the chunk compile queue's state, with vanilla's choice of task and none of its cost (SectionTaskDynamicQueueMixin).
 *
 * <p>Vanilla's SectionTaskDynamicQueue.poll scans every queued task for the nearest one, under the queue's lock, on every
 * poll, then removes it from the middle of an ArrayList: O(n) per task. Without shaders the queue holds a few tasks; with a
 * shader pack the off-camera shadow casters are queued too (BSL, RD 16: ~18,800 at world load), so the workers spent their
 * time scanning and waiting on the lock (JFR: poll + removeTaskByIndex were 13.8% of all CPU samples while loading).
 *
 * <p>Here the initial and recompile tasks are two binary heaps ordered by (squared distance from the camera to the task's
 * section centre, insertion number). The minimum of that order is exactly the task vanilla's scan picks: the nearest, and
 * on a tie the earliest in its list, which keeps insertion order. The distances are those of the camera passed to poll: when
 * it differs from the one the heaps were built for, they are rebuilt (O(n), once per camera change, not per poll). Cancelled
 * tasks are skipped and dropped, as vanilla's scan does; the recompile quota logic is vanilla's. size() may still count
 * cancelled tasks until a rebuild or a poll reaches them (it is only used for statistics and pacing heuristics).
 * -Dcadence.fastCompileQueue=false: vanilla's queue.
 */
public final class CompileQueue {
	public static final boolean ENABLED = !"false".equals(System.getProperty("cadence.fastCompileQueue"));
	/** -Dcadence.fastCompileQueue=verify: vanilla's queue runs too and decides; every poll's two choices are compared and logged. */
	public static final boolean VERIFY = "verify".equals(System.getProperty("cadence.fastCompileQueue"));
	private static long verifyPolls, verifyMismatches;

	public static void verify(SectionTask ours, SectionTask vanilla) {
		verifyPolls++;
		if (ours != vanilla) {
			verifyMismatches++;
			if (verifyMismatches <= 10) org.slf4j.LoggerFactory.getLogger("cadence").info("[compile-queue verify] mismatch #{} at poll {}: ours {} vanilla {}",
				verifyMismatches, verifyPolls, ours == null ? null : ours.getRenderOrigin(), vanilla == null ? null : vanilla.getRenderOrigin());
		}
		if (verifyPolls % 5000 == 0) org.slf4j.LoggerFactory.getLogger("cadence").info("[compile-queue verify] {} polls, {} mismatches", verifyPolls, verifyMismatches);
	}

	private final Heap initial = new Heap(), recompile = new Heap();
	private double cx = Double.NaN, cy, cz;
	private long sequence;
	private volatile int size;

	/** Caller holds the queue's lock (vanilla's methods are synchronized). */
	public void add(SectionTask task) {
		Entry e = new Entry(task, sequence++);
		if (!Double.isNaN(cx)) e.distance = distance(task, cx, cy, cz);
		(task.isRecompile() ? recompile : initial).push(e, !Double.isNaN(cx));
		size++;
	}

	/** Vanilla's poll: the nearest initial task, unless a nearer recompile task is due (at most 2 in a row), or only recompiles. */
	public SectionTask poll(Vec3 camera, int[] recompileQuota) {
		if (camera.x != cx || camera.y != cy || camera.z != cz) {
			cx = camera.x;
			cy = camera.y;
			cz = camera.z;
			initial.rebuild(cx, cy, cz);
			recompile.rebuild(cx, cy, cz);
		}
		Entry a = initial.peekLive(), b = recompile.peekLive();
		SectionTask result;
		if (b != null && (a == null || recompileQuota[0] > 0 && b.distance < a.distance)) {
			recompileQuota[0]--;
			result = recompile.pop().task;
		} else {
			recompileQuota[0] = 2;
			result = a == null ? null : initial.pop().task;
		}
		size = initial.size + recompile.size;
		return result;
	}

	public void clear() {
		initial.cancelAll();
		recompile.cancelAll();
		size = 0;
	}

	public int size() {
		return size;
	}

	private static double distance(SectionTask task, double x, double y, double z) {
		return task.getRenderOrigin().distToCenterSqr(x, y, z);
	}

	private static boolean cancelled(SectionTask task) {
		return ((SectionTaskAccessor) task).cadence$isCancelled().get();
	}

	private static final class Entry {
		final SectionTask task;
		final long order;
		double distance;

		Entry(SectionTask task, long order) {
			this.task = task;
			this.order = order;
		}

		boolean before(Entry o) {
			return distance < o.distance || distance == o.distance && order < o.order;
		}
	}

	/** A binary min-heap of entries (array based; Floyd's O(n) rebuild). */
	private static final class Heap {
		Entry[] items = new Entry[64];
		int size;
		/** false until the first camera is known: entries are then kept unordered until rebuild. */
		boolean ordered;

		void push(Entry e, boolean haveCamera) {
			if (size == items.length) items = Arrays.copyOf(items, size * 2);
			items[size++] = e;
			if (haveCamera && ordered) up(size - 1);
		}

		/** The nearest task that isn't cancelled (cancelled ones on top are dropped), or null. */
		Entry peekLive() {
			while (size > 0 && cancelled(items[0].task)) pop();
			return size > 0 ? items[0] : null;
		}

		Entry pop() {
			Entry top = items[0];
			items[0] = items[--size];
			items[size] = null;
			if (size > 0) down(0);
			return top;
		}

		void rebuild(double x, double y, double z) {
			int n = 0;
			for (int i = 0; i < size; i++) {
				Entry e = items[i];
				if (cancelled(e.task)) continue;
				e.distance = distance(e.task, x, y, z);
				items[n++] = e;
			}
			Arrays.fill(items, n, size, null);
			size = n;
			for (int i = size / 2 - 1; i >= 0; i--) down(i);
			ordered = true;
		}

		void cancelAll() {
			for (int i = 0; i < size; i++) {
				items[i].task.cancel();
				items[i] = null;
			}
			size = 0;
		}

		private void up(int i) {
			Entry e = items[i];
			while (i > 0) {
				int parent = (i - 1) >>> 1;
				if (!e.before(items[parent])) break;
				items[i] = items[parent];
				i = parent;
			}
			items[i] = e;
		}

		private void down(int i) {
			Entry e = items[i];
			int half = size >>> 1;
			while (i < half) {
				int child = 2 * i + 1;
				if (child + 1 < size && items[child + 1].before(items[child])) child++;
				if (!items[child].before(e)) break;
				items[i] = items[child];
				i = child;
			}
			items[i] = e;
		}
	}
}
