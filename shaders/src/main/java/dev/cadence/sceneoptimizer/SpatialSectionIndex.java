// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import dev.cadence.scene.SceneVolume;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.world.phys.AABB;

/**
 * Immutable broad phase for stable section bounds. Cell unions reject spatial groups; a bit set
 * restores source iteration order so grouping cannot reorder translucent or overlapping geometry.
 * Rebuild only when section positions change, not when a view or a mesh changes.
 */
public final class SpatialSectionIndex<T> {
  private static final int CELL_SIZE = 64;
  private final List<Entry<T>> entries;
  private final List<Cell> cells;

  public record Entry<T>(T value, AABB bounds) {}

  public record Query(BitSet candidates, BitSet fullyInside, int coarseTests) {}

  public SpatialSectionIndex(List<Entry<T>> entries) {
    this.entries = List.copyOf(entries);
    var builders = new LinkedHashMap<CellPosition, CellBuilder>();
    for (int i = 0; i < entries.size(); i++) {
      if ((i & 63) == 0 && Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException("Obsolete spatial index build");
      AABB bounds = entries.get(i).bounds();
      var position = new CellPosition(cell(bounds.minX), cell(bounds.minY), cell(bounds.minZ));
      builders.computeIfAbsent(position, ignored -> new CellBuilder()).add(i, bounds);
    }
    var built = new ArrayList<Cell>(builders.size());
    for (CellBuilder builder : builders.values()) {
      if (Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException("Obsolete spatial index build");
      int[] members = new int[builder.members.size()];
      for (int i = 0; i < members.length; i++) members[i] = builder.members.get(i);
      built.add(new Cell(builder.bounds, members));
    }
    cells = List.copyOf(built);
  }

  public Query query(SceneVolume volume) {
    var candidates = new BitSet(entries.size());
    var fullyInside = new BitSet(entries.size());
    for (Cell cell : cells) {
      SceneVolume.Classification classification = volume.classify(cell.bounds);
      if (classification != SceneVolume.Classification.OUTSIDE) {
        for (int member : cell.members) {
          candidates.set(member);
          if (classification == SceneVolume.Classification.INSIDE) fullyInside.set(member);
        }
      }
    }
    return new Query(candidates, fullyInside, cells.size());
  }

  public Entry<T> entry(int index) {
    return entries.get(index);
  }

  public int size() {
    return entries.size();
  }

  private static int cell(double coordinate) {
    return (int) Math.floor(coordinate / CELL_SIZE);
  }

  private record CellPosition(int x, int y, int z) {}

  private record Cell(AABB bounds, int[] members) {}

  private static final class CellBuilder {
    private AABB bounds;
    private final ArrayList<Integer> members = new ArrayList<>();

    private void add(int member, AABB box) {
      bounds = bounds == null ? box : bounds.minmax(box);
      members.add(member);
    }
  }
}
