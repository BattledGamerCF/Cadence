// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * A compile publishing across a reset. Our store (OwnRenderSectionMixin.cadence$ownStore) runs on the
 * worker after the compile's last cancellation check and publishes the mesh there (checkSectionMesh -> setSectionMesh -> our publish
 * event), while RenderSection.reset (render thread: a world / dimension change, a renderer reset, or the view area moving the section
 * to a new position) cancels the task and swaps the mesh out WITHOUT the dispatcher's lock (only the release after it takes it). So a
 * cancelled compile could publish its mesh after the reset: old geometry in the slot (at the new origin once setSectionNode has run),
 * and, if the section's new compile had already published, over it with nothing left to recompile it. Vanilla's own uploads publish from
 * the render thread (upload callbacks), the thread reset runs on, so they can't interleave this way.
 *
 * -Dcadence.own.int.lifeFix: reset holds the dispatcher's lock for its whole body (the cancel and the mesh swap included:
 * OwnIntLifeSectionMixin), and our store, under that lock, doesn't publish a cancelled compile's mesh but releases it (as vanilla's
 * own cancellation path does). Either the store publishes first (old mesh at the old origin, then reset clears and releases it, as for
 * any mesh) or the reset runs first and the store sees the cancellation. Nothing changes when no section is reset during a compile.
 * -Dcadence.own.int.lifeCheck (measurement): counts cancelled store attempts, those that would complete the mesh (and whether the resident
 * mesh is newer, by compile start time), the stale meshes actually published (without the fix) and whether they replaced a newer one, and
 * every 256 frames scans the view area for sections whose mesh was compiled for another position. -Dcadence.own.int.lifeLockStats: reset's
 * wait for and hold of the lock. Known limit, shared with vanilla: the empty-result branch of CompileTask.doTask publishes (setSectionMesh)
 * outside the lock without a cancellation re-check; see OWN-RENDERER.md. -Dcadence.own.int.lifeDelay=N:US (test only): every N-th store sleeps US microseconds before taking the lock (widens the window).
 */
public final class OwnLife {
	public static final boolean FIX = Boolean.getBoolean("cadence.own.int.lifeFix");
	public static final boolean CHECK = Boolean.getBoolean("cadence.own.int.lifeCheck");
	public static final boolean ON = FIX || CHECK;
	private static final int DELAY_EVERY, DELAY_US;
	static {
		String[] d = System.getProperty("cadence.own.int.lifeDelay", "0:0").split(":");
		DELAY_EVERY = Integer.parseInt(d[0]);
		DELAY_US = d.length > 1 ? Integer.parseInt(d[1]) : 0;
	}
	private static final ThreadLocal<Object> TASK = new ThreadLocal<>();
	private static final ThreadLocal<long[]> TASK_NODE = ThreadLocal.withInitial(() -> new long[1]);
	/** CHECK: each published compiled mesh -> the section position it was compiled for. */
	private static final Map<Object, Long> NODE = Collections.synchronizedMap(new WeakHashMap<>());
	private static final AtomicLong STORES = new AtomicLong(), ATTEMPTS = new AtomicLong(), FINAL = new AtomicLong(), FINAL_OVER_NEWER = new AtomicLong(),
		PUBLISHED = new AtomicLong(), OVER_NEWER = new AtomicLong(), SUPPRESSED = new AtomicLong();
	private static long residentMax, lastPrinted = -1, frames;

	private OwnLife() {
	}

	/**
	 * Worker (or render thread for compileSync): a compile task's doTask begins. Returns the previous context, which exit() restores
	 * (OwnIntLifeTaskMixin wraps doTask in try/finally, so an exception can't leave this thread pointing at the task).
	 */
	public static Object[] enter(Object task, long sectionNode) {
		Object[] prev = {TASK.get(), TASK_NODE.get()[0]};
		TASK.set(task);
		TASK_NODE.get()[0] = sectionNode;
		return prev;
	}

	public static void exit(Object[] prev) {
		TASK.set(prev[0]);
		TASK_NODE.get()[0] = (long) prev[1];
	}

	/** Our store, before it takes the lock (test only). */
	public static void delay() {
		if (DELAY_EVERY > 0 && STORES.incrementAndGet() % DELAY_EVERY == 0) {
			try {
				Thread.sleep(DELAY_US / 1000, (DELAY_US % 1000) * 1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Our store, under the dispatcher's lock, before it marks the layer uploaded and publishes: true when the compile was cancelled and
	 * the fix is on (the caller then releases the mesh instead of publishing it). CHECK counts every cancelled store attempt, those that
	 * would complete the mesh (every other drawn layer already uploaded: checkSectionMesh would publish), and among those the ones whose
	 * resident mesh is NEWER (compiled by a task that started later).
	 */
	public static boolean preStore(SectionRenderDispatcher.RenderSection section, CompiledSectionMesh key, ChunkSectionLayer layer, AtomicBoolean cancelled) {
		boolean c = cancelled != null && cancelled.get();
		if (CHECK && c) {
			ATTEMPTS.incrementAndGet();
			if (completes(key, layer) && section.getSectionMesh() != key) {
				FINAL.incrementAndGet();
				if (newer(section.getSectionMesh(), key)) FINAL_OVER_NEWER.incrementAndGet();
			}
		}
		if (c && FIX) {
			SUPPRESSED.incrementAndGet();
			return true;
		}
		return false;
	}

	/** CHECK, after checkSectionMesh (still under the lock): what this store actually published. prev: the resident mesh before it. */
	public static void postStore(SectionRenderDispatcher.RenderSection section, CompiledSectionMesh key, SectionMesh prev, AtomicBoolean cancelled) {
		if (!CHECK) return;
		if (section.getSectionMesh() == key && prev != key) {
			NODE.put(key, TASK_NODE.get()[0]);
			if (cancelled != null && cancelled.get()) {
				PUBLISHED.incrementAndGet();
				if (newer(prev, key)) OVER_NEWER.incrementAndGet();
			}
		}
	}

	private static boolean completes(CompiledSectionMesh key, ChunkSectionLayer layer) {
		for (ChunkSectionLayer l : ChunkSectionLayer.values()) {
			if (l == layer || key.getSectionDraw(l) == null) continue;
			if (!key.isVertexBufferUploaded(l) || !key.isIndexBufferUploaded(l)) return false;
		}
		return true;
	}

	private static boolean newer(SectionMesh resident, CompiledSectionMesh key) {
		return resident instanceof CompiledSectionMesh r && r != key && r.getCompileTaskStartTime() > key.getCompileTaskStartTime();
	}

	// ---- -Dcadence.own.int.lifeLockStats: reset()'s wait for and hold of the dispatcher lock (render thread), windows of up to 8192 ----

	public static final boolean LOCK_STATS = Boolean.getBoolean("cadence.own.int.lifeLockStats");
	private static final long[] waitNs = new long[8192], holdNs = new long[8192];
	private static int lockN;
	private static long lockWindowStart, lockTotal, lockMaxWait, lockMaxHold;

	public static synchronized void lockStats(long wait, long hold) {
		if (lockN < waitNs.length) {
			waitNs[lockN] = wait;
			holdNs[lockN] = hold;
			lockN++;
		}
		lockTotal++;
		lockMaxWait = Math.max(lockMaxWait, wait);
		lockMaxHold = Math.max(lockMaxHold, hold);
		long now = System.nanoTime();
		if (lockWindowStart == 0) lockWindowStart = now;
		if (lockN == waitNs.length || now - lockWindowStart > 2_000_000_000L && lockN >= 50) {
			long[] w = java.util.Arrays.copyOf(waitNs, lockN), h = java.util.Arrays.copyOf(holdNs, lockN);
			java.util.Arrays.sort(w);
			java.util.Arrays.sort(h);
			System.out.printf("cadence-own-life-lock: %d resets in %.1f s: wait us p50 %.1f p99 %.1f max %.1f | hold us p50 %.1f p99 %.1f max %.1f | run max wait %.1f hold %.1f (%d resets)%n",
				lockN, (now - lockWindowStart) / 1e9, w[lockN / 2] / 1e3, w[Math.min(lockN - 1, lockN * 99 / 100)] / 1e3, w[lockN - 1] / 1e3,
				h[lockN / 2] / 1e3, h[Math.min(lockN - 1, lockN * 99 / 100)] / 1e3, h[lockN - 1] / 1e3, lockMaxWait / 1e3, lockMaxHold / 1e3, lockTotal);
			lockN = 0;
			lockWindowStart = now;
		}
	}

	// ---- -Dcadence.own.int.lifeThrowTest=N (test only): the N-th reset throws inside the wrapped body; the wrapper checks the lock after ----

	public static final int THROW_TEST = Integer.getInteger("cadence.own.int.lifeThrowTest", 0);
	private static final AtomicLong RESETS = new AtomicLong();

	public static final class TestException extends RuntimeException {
		TestException() {
			super("cadence-own-life: reset exception test", null, false, false);
		}
	}

	public static void maybeThrow() {
		if (THROW_TEST > 0 && RESETS.incrementAndGet() == THROW_TEST) throw new TestException();
	}

	/** After the test exception left reset's wrapper: is the lock still held by this thread, and can another thread take it? */
	public static void verifyUnlocked(java.util.concurrent.locks.ReentrantLock lock) {
		boolean heldHere = lock.isHeldByCurrentThread();
		boolean[] other = new boolean[1];
		Thread t = new Thread(() -> {
			try {
				if (lock.tryLock(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
					other[0] = true;
					lock.unlock();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "cadence-life-lock-test");
		t.start();
		try {
			t.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		System.out.println("cadence-own-life: reset threw (test, reset #" + THROW_TEST + "); dispatcher lock still held by the render thread: " + heldHere
			+ ", hold count " + lock.getHoldCount() + "; another thread acquired it within 500 ms: " + other[0]);
	}

	/** The calling thread's compile task, if one is running (doTask brackets it). */
	public static Object task() {
		return TASK.get();
	}

	/** CHECK, render thread, each frame: every 256 frames count sections holding a mesh compiled for another position. */
	public static void scan(Iterable<SectionRenderDispatcher.RenderSection> sections) {
		if (!CHECK || ++frames % 256 != 0) return;
		long resident = 0;
		for (SectionRenderDispatcher.RenderSection s : sections) {
			if (s == null) continue;
			Long n = NODE.get(s.getSectionMesh());
			if (n != null && n != s.getSectionNode()) resident++;
		}
		residentMax = Math.max(residentMax, resident);
		long key = ATTEMPTS.get() * 1_000_003L + PUBLISHED.get() * 1009 + resident;
		if (key != lastPrinted) {
			lastPrinted = key;
			System.out.println("cadence-own-life: cancelled store attempts " + ATTEMPTS.get() + ", completing the mesh " + FINAL.get() + " (resident mesh newer: "
				+ FINAL_OVER_NEWER.get() + "); stale meshes actually published " + PUBLISHED.get() + " (replacing a newer mesh: " + OVER_NEWER.get()
				+ "); suppressed by the fix " + SUPPRESSED.get() + "; sections holding a mesh compiled for another position: now " + resident + ", max "
				+ residentMax);
		}
	}
}
