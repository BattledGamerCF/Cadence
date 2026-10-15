package dev.cadence.shaders.cadence.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

/**
 * Cadence: runs world generation steps in parallel while keeping the result of running them one at a time.
 *
 * <p>Each step names the chunks it may touch (its keys). Every key has a queue of the steps that named it, in submission
 * order; a step starts only when it is first in the queue of every one of its keys. So a step never runs alongside, and
 * never before, an earlier-submitted step that shares a chunk with it; steps with no chunk in common commute (they touch
 * disjoint chunks), so any interleaving gives the same chunks as the submission order. The wait-for relation always points
 * at earlier steps: no deadlock.
 *
 * <p>Used by one ChunkMap; submit is called from its worldgen thread, finish from any thread.
 */
public final class RegionScheduler {
	private final Long2ObjectOpenHashMap<ArrayDeque<Task>> queues = new Long2ObjectOpenHashMap<>();
	private long submitted, waited, maxRunning, running;

	/** A step: its keys and what to do when it may start. */
	public static final class Task {
		final long[] keys;
		final java.util.function.Consumer<Task> body;
		final Executor executor; // null: the submitter waits and runs it itself
		CountDownLatch latch;
		boolean started;

		Task(long[] keys, java.util.function.Consumer<Task> body, Executor executor) {
			this.keys = keys;
			this.body = body;
			this.executor = executor;
		}
	}

	/**
	 * Submits a step. With an executor, the body runs there once the step may start (it must call {@link #finish} with the
	 * task it is given).
	 * Without, this thread waits until the step may start and returns; the caller runs the body and then calls finish.
	 */
	public Task submit(long[] keys, java.util.function.Consumer<Task> body, Executor executor) {
		Task task = new Task(keys, body, executor);
		boolean ready;
		synchronized (this) {
			submitted++;
			ready = true;
			for (long key : keys) {
				ArrayDeque<Task> q = queues.get(key);
				if (q == null) queues.put(key, q = new ArrayDeque<>(2));
				if (!q.isEmpty()) ready = false;
				q.addLast(task);
			}
			if (ready) {
				task.started = true;
				running++;
				maxRunning = Math.max(maxRunning, running);
			} else {
				waited++;
				if (executor == null) task.latch = new CountDownLatch(1);
			}
		}
		if (ready) {
			if (executor != null) executor.execute(() -> body.accept(task));
		} else if (executor == null) {
			try {
				task.latch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted waiting for a world generation region", e);
			}
		}
		return task;
	}

	/**
	 * Takes the keys at once if no earlier step holds any of them (returns the task: the caller runs the step and calls
	 * finish); otherwise queues the step to run on {@code executor} when they free up and returns null.
	 */
	public Task submitNowOrLater(long[] keys, java.util.function.Consumer<Task> later, Executor executor) {
		Task task = new Task(keys, later, executor);
		synchronized (this) {
			submitted++;
			boolean ready = true;
			for (long key : keys) {
				ArrayDeque<Task> q = queues.get(key);
				if (q == null) queues.put(key, q = new ArrayDeque<>(2));
				if (!q.isEmpty()) ready = false;
				q.addLast(task);
			}
			if (!ready) {
				waited++;
				return null;
			}
			task.started = true;
			running++;
			maxRunning = Math.max(maxRunning, running);
			return task;
		}
	}

	/** The step is done: later steps sharing its chunks may start. */
	public void finish(Task task) {
		ArrayDeque<Task> startable = null;
		synchronized (this) {
			running--;
			for (long key : task.keys) {
				ArrayDeque<Task> q = queues.get(key);
				Task head = q.pollFirst();
				if (head != task) throw new IllegalStateException("World generation region queue out of order");
				if (q.isEmpty()) {
					queues.remove(key);
					continue;
				}
				Task next = q.peekFirst();
				if (!next.started && headOfAll(next)) {
					next.started = true;
					running++;
					maxRunning = Math.max(maxRunning, running);
					if (startable == null) startable = new ArrayDeque<>(2);
					startable.add(next);
				}
			}
		}
		if (startable != null) for (Task next : startable) {
			if (next.executor != null) {
				Task t = next;
				next.executor.execute(() -> t.body.accept(t));
			}
			else next.latch.countDown();
		}
	}

	private boolean headOfAll(Task task) {
		for (long key : task.keys) if (queues.get(key).peekFirst() != task) return false;
		return true;
	}

	public synchronized String stats() {
		return submitted + " steps, " + waited + " waited for an earlier one, at most " + maxRunning + " at once";
	}
}
