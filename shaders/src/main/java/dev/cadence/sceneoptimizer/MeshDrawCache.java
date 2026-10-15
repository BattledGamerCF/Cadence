// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.DynamicGpuData.IndexedDraw;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSectionBufferSlice;

/**
 * Bounded metadata for one mesh. Allocation snapshots are invalidated by actual allocator mutation,
 * not just mesh identity: a translucent re-sort can replace its index allocation in place.
 * Immutable draw records contain only integers and are reused only if every constructor argument
 * matches.
 */
public final class MeshDrawCache implements AutoCloseable {
  private final AtomicReference<AllocationSnapshot> allocations =
      new AtomicReference<>(new AllocationSnapshot());
  private final IndexedDraw[] recentDraws = new IndexedDraw[SectionLayerSets.count() * 2];

  public AllocationSnapshot allocations() {
    return allocations.get();
  }

  /** Query only while holding the dispatcher's existing allocation/copy lock. */
  public SliceEntry findSlice(
      AllocationSnapshot snapshot, Object dispatcher, ChunkSectionLayer layer) {
    SliceEntry result =
        snapshot.owner.get() == dispatcher ? snapshot.entries[layer.ordinal()] : null;
    if (result == null) DrawMetadataMetrics.sliceMisses++;
    else DrawMetadataMetrics.sliceHits++;
    return result;
  }

  /** Do not publish a lookup result if an allocation event invalidated its captured generation. */
  public void rememberSlice(
      AllocationSnapshot snapshot,
      Object dispatcher,
      ChunkSectionLayer layer,
      RenderSectionBufferSlice slice) {
    SliceEntry[] entries =
        snapshot.owner.get() == dispatcher
            ? snapshot.entries.clone()
            : new SliceEntry[SectionLayerSets.count()];
    entries[layer.ordinal()] = new SliceEntry(slice);
    AllocationSnapshot replacement = new AllocationSnapshot(dispatcher, entries);
    if (allocations.compareAndSet(snapshot, replacement)) {
      DrawMetadataMetrics.retainedSlices.add(replacement.size - snapshot.size);
    }
  }

  /** Each call creates a new generation token, including invalidation of an empty/null result. */
  public void invalidateAllocations() {
    AllocationSnapshot old = allocations.getAndSet(new AllocationSnapshot());
    DrawMetadataMetrics.retainedSlices.add(-old.size);
    DrawMetadataMetrics.invalidations.increment();
  }

  public IndexedDraw findDraw(
      ChunkSectionLayer layer,
      int indexCount,
      int instanceCount,
      int firstIndex,
      int baseVertex,
      int baseInstance) {
    int slot = layer.ordinal() * 2;
    IndexedDraw result = recentDraws[slot];
    if (!matches(result, indexCount, instanceCount, firstIndex, baseVertex, baseInstance)) {
      result = recentDraws[slot + 1];
      if (!matches(result, indexCount, instanceCount, firstIndex, baseVertex, baseInstance)) {
        DrawMetadataMetrics.drawMisses++;
        return null;
      }
      recentDraws[slot + 1] = recentDraws[slot];
      recentDraws[slot] = result;
    }
    DrawMetadataMetrics.drawHits++;
    return result;
  }

  public void rememberDraw(ChunkSectionLayer layer, IndexedDraw draw) {
    int slot = layer.ordinal() * 2;
    if (recentDraws[slot + 1] == null) DrawMetadataMetrics.retainedDraws.increment();
    recentDraws[slot + 1] = recentDraws[slot];
    recentDraws[slot] = draw;
  }

  @Override
  public void close() {
    invalidateAllocations();
    for (int i = 0; i < recentDraws.length; i++) {
      if (recentDraws[i] != null) {
        recentDraws[i] = null;
        DrawMetadataMetrics.retainedDraws.decrement();
      }
    }
  }

  private static boolean matches(
      IndexedDraw draw,
      int indexCount,
      int instanceCount,
      int firstIndex,
      int baseVertex,
      int baseInstance) {
    return draw != null
        && draw.indexCount() == indexCount
        && draw.instanceCount() == instanceCount
        && draw.firstIndex() == firstIndex
        && draw.baseVertex() == baseVertex
        && draw.baseInstance() == baseInstance;
  }

  /** A non-null entry can deliberately cache a missing allocation. */
  public record SliceEntry(RenderSectionBufferSlice slice) {}

  public static final class AllocationSnapshot {
    private final WeakReference<Object> owner;
    private final SliceEntry[] entries;
    private final int size;

    private AllocationSnapshot() {
      this(null, new SliceEntry[SectionLayerSets.count()]);
    }

    private AllocationSnapshot(Object owner, SliceEntry[] entries) {
      this.owner = new WeakReference<>(owner);
      this.entries = entries;
      int count = 0;
      for (SliceEntry entry : entries) if (entry != null) count++;
      size = count;
    }
  }
}
