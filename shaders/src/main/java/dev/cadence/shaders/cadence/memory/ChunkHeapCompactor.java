package dev.cadence.shaders.cadence.memory;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.datafixers.util.Pair;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.cadence.shaders.cadence.mixin.memory.ChunkHeapCompactStepMixin;
import dev.cadence.shaders.cadence.mixin.memory.SectionUberBuffersAccess;
import dev.cadence.shaders.cadence.mixin.memory.UberGpuBufferAccess;
import dev.cadence.shaders.cadence.mixin.memory.UberGpuBufferHeapAccess;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.slf4j.LoggerFactory;

/**
 * Cadence: gives back chunk geometry heaps that moving around left mostly empty.
 *
 * <p>Vanilla keeps chunk meshes in per-layer "uber buffers" made of fixed-size GPU heaps (first fit, TLSF) and frees a heap
 * only once it holds nothing at all; one surviving mesh keeps a whole heap. After travelling, the heaps were measured at
 * 305 MB used of 584 MB reserved (BSL, RD 16) — on a Mac that is shared RAM. Every 2 s each uber buffer is checked: if its
 * emptiest heap is under half full and the other heaps have room for its contents, its allocations are moved, up to 4 MB
 * per frame, by GPU buffer copies into free space in the other heaps, and vanilla's allocation map is pointed at the new
 * place (draws look their slice up there every frame). Meanwhile the heap takes no new allocations. Once empty it is closed
 * the frame after (so nothing drawn in the frame it emptied can refer to it).
 *
 * <p>Safe points only: called at the end of SectionRenderDispatcher.uploadTerrainBuffersToGpu (render thread, under the
 * dispatcher's copy lock, after staged uploads were placed), and only when nothing is staged. The copies are encoded
 * before the frame's draws, in order on the same queue as everything else; the old bytes stay where they were until the
 * heap is closed, so a draw prepared before the move still reads valid data. -Dcadence.heapCompact=false: off.
 */
public final class ChunkHeapCompactor {
	public static volatile boolean enabled = !"false".equals(System.getProperty("cadence.heapCompact"));
	private static final long CHECK_NANOS = 2_000_000_000L;
	/** Cadence (MemoryGuard): under macOS memory pressure, heaps up to 70% full are emptied (else under half) and checked each second. */
	private static volatile boolean pressure;

	public static void pressure(int level) {
		pressure = level > 0;
	}
	private static final long BUDGET = 4L << 20;
	private static final boolean LOG = Boolean.getBoolean("cadence.probe") || System.getProperty("cadence.selftest") != null;
	private static long lastCheck;
	private static long movedBytes, movedAllocations, heapsFreed, freedBytes, aborted, logged;

	/** Implemented on UberGpuBuffer by ChunkHeapCompactMixin. */
	public interface Holder {
		TlsfAllocator cadence$evacuating();

		void cadence$setEvacuating(TlsfAllocator allocator);

		TlsfAllocator cadence$closing();

		void cadence$setClosing(TlsfAllocator allocator);
	}

	private ChunkHeapCompactor() {
	}

	public static void step(SectionRenderDispatcher dispatcher) {
		if (!enabled || !RenderSystem.isOnRenderThread()) return;
		boolean check = System.nanoTime() - lastCheck > (pressure ? CHECK_NANOS / 2 : CHECK_NANOS);
		if (check) lastCheck = System.nanoTime();
		long budget = BUDGET;
		for (Object layer : ((ChunkHeapCompactStepMixin) dispatcher).cadence$uberBuffers().values()) {
			var buffers = (SectionUberBuffersAccess) layer;
			budget = step(buffers.cadence$vertex(), check, budget);
			budget = step(buffers.cadence$index(), check, budget);
		}
		if (LOG && System.nanoTime() - logged > 30_000_000_000L && (movedAllocations > 0 || aborted > 0)) {
			logged = System.nanoTime();
			LoggerFactory.getLogger("cadence").info("Chunk heap compaction: {} heaps freed ({} MB), {} meshes moved ({} MB), {} aborted",
				heapsFreed, freedBytes >> 20, movedAllocations, movedBytes >> 20, aborted);
		}
	}

	private static long step(UberGpuBuffer<?> buffer, boolean check, long budget) {
		var access = (UberGpuBufferAccess) buffer;
		var holder = (Holder) buffer;
		List<Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap>> nodes = access.cadence$nodes();
		// last step's emptied heap: closed now, a frame after its last allocation moved out
		TlsfAllocator closing = holder.cadence$closing();
		if (closing != null) {
			holder.cadence$setClosing(null);
			for (int i = 0; i < nodes.size(); i++) {
				var node = nodes.get(i);
				if (node.getFirst() != closing) continue;
				if (closing.isCompletelyFree()) {
					((UberGpuBufferHeapAccess) node.getSecond()).cadence$gpuBuffer().close();
					nodes.remove(i);
					heapsFreed++;
					freedBytes += access.cadence$heapSize();
				}
				break;
			}
		}
		if (!access.cadence$staged().isEmpty()) return budget;
		Map<Object, TlsfAllocator.Allocation> allocations = access.cadence$allocations();
		TlsfAllocator evacuating = holder.cadence$evacuating();
		if (evacuating == null) {
			if (check && nodes.size() >= 2) choose(holder, nodes, allocations, access.cadence$heapSize());
			return budget;
		}
		Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap> from = null;
		for (var node : nodes) if (node.getFirst() == evacuating) from = node;
		if (from == null) {
			// vanilla closed it meanwhile (it emptied on its own)
			holder.cadence$setEvacuating(null);
			return budget;
		}
		if (budget <= 0) return budget;
		GpuBuffer source = ((UberGpuBufferHeapAccess) from.getSecond()).cadence$gpuBuffer();
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		int align = access.cadence$alignSize();
		// TlsfAllocator.allocate(s, align) reserves a block of alignUp(s + pad, 32) and places the data up to pad bytes in:
		// blockSize - pad covers the data, stays inside the old block, and a new allocation of it keeps the block size
		long pad = Integer.bitCount(align) == 1 && align <= 32 ? 0 : align;
		long heapSize = access.cadence$heapSize();
		for (var entry : allocations.entrySet()) {
			if (budget <= 0) break;
			TlsfAllocator.Allocation old = entry.getValue();
			if (old.getHeap() != from.getSecond()) continue;
			long size = old.getSize() - pad;
			if (size <= 0 || old.getOffsetFromHeap() + size > heapSize) {
				// not the layout assumed above: leave this heap alone
				holder.cadence$setEvacuating(null);
				aborted++;
				return budget;
			}
			TlsfAllocator.Allocation moved = null;
			Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap> to = null;
			for (var node : nodes) {
				if (node.getFirst() == evacuating) continue;
				moved = node.getFirst().allocate(size, align);
				if (moved != null) {
					to = node;
					break;
				}
			}
			if (moved == null) {
				// the others filled up: stop here (what moved stays moved, all of it valid)
				holder.cadence$setEvacuating(null);
				aborted++;
				return budget;
			}
			if (moved.getOffsetFromHeap() + size > heapSize) {
				to.getFirst().free(moved);
				holder.cadence$setEvacuating(null);
				aborted++;
				return budget;
			}
			GpuBuffer target = ((UberGpuBufferHeapAccess) to.getSecond()).cadence$gpuBuffer();
			encoder.copyToBuffer(source.slice(old.getOffsetFromHeap(), size), target.slice(moved.getOffsetFromHeap(), size));
			entry.setValue(moved);
			evacuating.free(old);
			budget -= size;
			movedBytes += size;
			movedAllocations++;
		}
		if (evacuating.isCompletelyFree()) {
			holder.cadence$setEvacuating(null);
			holder.cadence$setClosing(evacuating);
		}
		return budget;
	}

	/** The emptiest heap, if it is under half full and fits in the free space of the others (with a margin for fragments). */
	private static void choose(Holder holder, List<Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap>> nodes,
		Map<Object, TlsfAllocator.Allocation> allocations, long heapSize) {
		var used = new IdentityHashMap<Object, long[]>();
		for (var node : nodes) used.put(node.getSecond(), new long[1]);
		for (var allocation : allocations.values()) {
			long[] u = used.get(allocation.getHeap());
			if (u != null) u[0] += allocation.getSize();
		}
		Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap> emptiest = null;
		long least = Long.MAX_VALUE, total = 0;
		for (var node : nodes) {
			long u = used.get(node.getSecond())[0];
			total += u;
			if (u < least) {
				least = u;
				emptiest = node;
			}
		}
		long othersFree = (nodes.size() - 1) * heapSize - (total - least);
		long limit = pressure ? heapSize * 7 / 10 : heapSize / 2;
		if (emptiest != null && least > 0 && least < limit && othersFree >= least + least / 4 + heapSize / 16)
			holder.cadence$setEvacuating(emptiest.getFirst());
	}
}
