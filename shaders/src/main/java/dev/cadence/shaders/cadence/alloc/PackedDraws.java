package dev.cadence.shaders.cadence.alloc;

import java.util.ArrayList;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * Cadence: vanilla's per-frame chunk section draw commands without one object per draw.
 *
 * <p>LevelRenderer.extractSectionDrawGroups builds, every frame and for every visible section and layer, an IndexedDraw
 * record (5 ints) in a fresh ArrayList per draw group, then DynamicGpuData copies them into a mapped GPU buffer. With
 * FaceCull's split draws that was ~17 MB/s of garbage on the render thread at RD 16 (a third of its allocation), and
 * the loader's record reuse rarely hits because each record carries the section's index in this frame's list. For the
 * opaque and cutout layers (translucent lists are reversed by vanilla and keep its own path) the group list is a pooled
 * {@link PackedDrawList}; the record constructor stashes its 5 ints and returns {@link #SENTINEL}, and the list's add
 * (FaceCull's split, or the list itself) stores the ints. The bytes written to the GPU buffer are the same, in the same
 * order. Lists go back to the pool when prepareChunkRendersIndirect returns (vanilla consumes them inside it). Render
 * thread only. -Dcadence.packedDraws=false: vanilla's lists.
 */
public final class PackedDraws {
	public static volatile boolean enabled = !"false".equals(System.getProperty("cadence.packedDraws"));
	/** Returned for a stashed draw; never stored anywhere but momentarily on the operand stack. */
	public static final DynamicGpuData.IndexedDraw SENTINEL = new DynamicGpuData.IndexedDraw(0, 0, 0, 0, 0);
	private static int indexCount, instanceCount, firstIndex, baseVertex, baseInstance;
	private static boolean stashed;
	private static Thread owner;
	private static final ArrayList<PackedDrawList> free = new ArrayList<>(), lent = new ArrayList<>();
	private static final boolean LOG = Boolean.getBoolean("cadence.probe") || System.getProperty("cadence.selftest") != null;
	private static long stashes, materialized, listsTaken, prepares, logged;

	private PackedDraws() {
	}

	/** prepareChunkRendersIndirect HEAD (also recycles lists an aborted call left lent) and RETURN. */
	public static void recycle() {
		owner = Thread.currentThread();
		prepares++;
		if (LOG && System.nanoTime() - logged > 30_000_000_000L) {
			logged = System.nanoTime();
			org.slf4j.LoggerFactory.getLogger("cadence").info("Packed draws: {} prepares, {} lists, {} draws stashed, {} materialized",
				prepares / 2, listsTaken, stashes, materialized);
		}
		for (int i = lent.size() - 1; i >= 0; i--) {
			PackedDrawList list = lent.get(i);
			list.clear();
			free.add(list);
		}
		lent.clear();
		stashed = false;
	}

	/** This layer's draw groups use packed lists (decided the same way for the list and for its records). */
	public static boolean packs(ChunkSectionLayer layer) {
		return enabled && !layer.translucent() && Thread.currentThread() == owner;
	}

	public static PackedDrawList take() {
		listsTaken++;
		PackedDrawList list = free.isEmpty() ? new PackedDrawList() : free.removeLast();
		lent.add(list);
		return list;
	}

	public static DynamicGpuData.IndexedDraw stash(int indexCount, int instanceCount, int firstIndex, int baseVertex, int baseInstance) {
		PackedDraws.indexCount = indexCount;
		PackedDraws.instanceCount = instanceCount;
		PackedDraws.firstIndex = firstIndex;
		PackedDraws.baseVertex = baseVertex;
		PackedDraws.baseInstance = baseInstance;
		stashed = true;
		stashes++;
		return SENTINEL;
	}

	private static void consume() {
		if (!stashed) throw new IllegalStateException("No stashed draw for the sentinel");
		stashed = false;
	}

	static void addStashed(PackedDrawList list) {
		consume();
		list.add(indexCount, instanceCount, firstIndex, baseVertex, baseInstance);
	}

	/** The stashed draw as a record (a sentinel reaching a list that isn't packed). */
	public static DynamicGpuData.IndexedDraw materialize() {
		consume();
		materialized++;
		return new DynamicGpuData.IndexedDraw(indexCount, instanceCount, firstIndex, baseVertex, baseInstance);
	}

	public static int indexCount() {
		return indexCount;
	}

	public static int instanceCount() {
		return instanceCount;
	}

	public static int firstIndex() {
		return firstIndex;
	}

	public static int baseVertex() {
		return baseVertex;
	}

	public static int baseInstance() {
		return baseInstance;
	}

	/** FaceCull took the stashed draw's numbers. */
	public static void taken() {
		consume();
	}
}
