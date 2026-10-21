package dev.cadence.metal.gen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;

/**
 * Cadence (OFF by default, -Dcadence.gen.surfaceBuffers=true; measured, no gain once RandomState's pools keep their buffers:
 * ABBA flights 1,342 / 1,838 vs 1,402 / 1,248 ms of young pauses; verify 2,000 buffers, 0 unwritten): the density buffers ore veins sample for a chunk's
 * surface pass (MaterialRuleContext.getDensitiesInChunk, DensityBuffer.createUnpooled: one ~140 KB float array per vein rule
 * per chunk) are reused. They live only inside MaterialSystem.buildSurface: the context and the compiled rules holding them are
 * that method's locals. So each thread keeps the buffers its finished passes used and hands them out again, same size only,
 * instead of allocating new arrays the young collector copies while they are still in use (30% of the large float arrays
 * allocated while flying over new terrain). sampleVolume writes every element of a buffer before anything reads it;
 * -Dcadence.gen.surfaceBufferVerify=true fills reused buffers with NaN first and checks at the end of each pass that none is
 * left, i.e. that nothing stale could have been read.
 */
public final class SurfaceBuffers {
	public static final boolean ON = Boolean.getBoolean("cadence.gen.surfaceBuffers");
	public static final boolean VERIFY = Boolean.getBoolean("cadence.gen.surfaceBufferVerify");
	private static final int KEEP = 8;
	private static final AtomicLong REUSED = new AtomicLong(), CHECKED = new AtomicLong(), STALE = new AtomicLong();

	private static final class Scope {
		int depth;
		final ArrayList<DensityBuffer> inUse = new ArrayList<>();
		final ArrayDeque<DensityBuffer> free = new ArrayDeque<>();
	}

	private static final ThreadLocal<Scope> SCOPE = ThreadLocal.withInitial(Scope::new);

	private SurfaceBuffers() {
	}

	/** MaterialSystem.buildSurface begins on this thread. */
	public static void enter() {
		SCOPE.get().depth++;
	}

	/** MaterialSystem.buildSurface returned: the outermost one gives the pass's buffers back. */
	public static void exit() {
		Scope s = SCOPE.get();
		if (s.depth == 0) return;
		if (--s.depth > 0) return;
		for (DensityBuffer b : s.inUse) {
			if (VERIFY) check(b);
			if (s.free.size() < KEEP) s.free.push(b);
		}
		s.inUse.clear();
	}

	/** Replaces DensityBuffer.createUnpooled(size) inside a surface pass; outside one, vanilla's. */
	public static DensityBuffer take(int size) {
		Scope s = SCOPE.get();
		if (s.depth == 0) return DensityBuffer.createUnpooled(size);
		DensityBuffer b = null;
		for (var it = s.free.iterator(); it.hasNext(); ) {
			DensityBuffer c = it.next();
			if (c.capacity() == size && c.size() == size) {
				it.remove();
				b = c;
				break;
			}
		}
		if (b == null) b = DensityBuffer.createUnpooled(size);
		else {
			REUSED.incrementAndGet();
			if (VERIFY) b.fill(Float.NaN);
		}
		s.inUse.add(b);
		return b;
	}

	private static void check(DensityBuffer b) {
		long checked = CHECKED.incrementAndGet();
		for (int i = 0, n = b.size(); i < n; i++)
			if (Float.isNaN(b.get(i))) {
				if (STALE.incrementAndGet() <= 5) System.out.println("cadence-gen: surfaceBuffers STALE element " + i + " of " + n);
				break;
			}
		if (checked % 2000 == 0 || checked == 100)
			System.out.println("cadence-gen: surfaceBuffers checked " + checked + " buffers (" + REUSED.get() + " reused), " + STALE.get() + " with unwritten elements");
	}
}
