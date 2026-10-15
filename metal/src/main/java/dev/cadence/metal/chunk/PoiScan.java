// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.chunk;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import dev.cadence.metal.mixin.chunk.PoiSectionAccessor;
import dev.cadence.metal.mixin.chunk.SectionStorageAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;

/**
 * D1 (-Dcadence.chunk.poi=true): PoiManager.findAllClosestFirstWithType as plain loops, for AcquirePoi, which consumes the stream
 * at once (limit 5, filter, collect). Vanilla's pipeline: chunks of ChunkPos.rangeClosed (x fastest, then z) around the centre,
 * sections bottom to top (getOrLoad: may load POI data from disk), each section's byType entries whose type passes, each record
 * in its set that passes the occupancy test, then the square, the radius and the caller's filter (AcquirePoi's retry cache, which
 * has side effects), then a stable sort by distance. sorted() pulls every upstream element before the first result, so running
 * the same steps here, in the same order, once, gives the same list and the same side effects in the same order. List.sort is
 * the same stable merge sort with the same comparator.
 */
public final class PoiScan {
	private PoiScan() { }

	/**
	 * poi=verify: vanilla's stream runs for real with the caller's filter wrapped to record every call and its answer; then the
	 * loops run with a filter that replays those answers and checks it is asked about the same positions in the same order;
	 * the two sorted lists must be equal. Vanilla's result is what the game gets.
	 */
	public static Stream<Pair<Holder<PoiType>, BlockPos>> verify(
		PoiManager poi, Predicate<Holder<PoiType>> predicate, Predicate<BlockPos> filter, BlockPos center, int radius, PoiManager.Occupancy occupancy
	) {
		List<BlockPos> asked = new ArrayList<>();
		List<Boolean> answers = new ArrayList<>();
		List<Pair<Holder<PoiType>, BlockPos>> vanilla = poi.findAllClosestFirstWithType(predicate, pos -> {
			boolean r = filter.test(pos);
			asked.add(pos.immutable());
			answers.add(r);
			return r;
		}, center, radius, occupancy).toList();
		int[] k = {0};
		boolean[] sameCalls = {true};
		List<Pair<Holder<PoiType>, BlockPos>> mine = closestFirstWithType(poi, predicate, pos -> {
			int i = k[0]++;
			if (i >= asked.size() || !asked.get(i).equals(pos)) {
				sameCalls[0] = false;
				return false;
			}
			return answers.get(i);
		}, center, radius, occupancy).toList();
		boolean same = sameCalls[0] && k[0] == asked.size() && vanilla.equals(mine);
		ChunkOpt.count(same ? "poi.verify.same" : "poi.verify.MISMATCH");
		ChunkOpt.count("poi.verify.filterCalls", asked.size());
		ChunkOpt.count("poi.verify.results", vanilla.size());
		return vanilla.stream();
	}

	@SuppressWarnings("unchecked")
	public static Stream<Pair<Holder<PoiType>, BlockPos>> closestFirstWithType(
		PoiManager poi, Predicate<Holder<PoiType>> predicate, Predicate<BlockPos> filter, BlockPos center, int radius, PoiManager.Occupancy occupancy
	) {
		SectionStorageAccess storage = (SectionStorageAccess) poi;
		LevelHeightAccessor height = storage.cadence$levelHeightAccessor();
		int minY = height.getMinSectionY(), maxY = height.getMaxSectionY();
		int chunkRadius = Math.floorDiv(radius, 16) + 1;
		ChunkPos c = ChunkPos.containing(center);
		long radiusSqr = (long) radius * radius;
		Predicate<? super PoiRecord> occupied = occupancy.getTest();
		List<Pair<Holder<PoiType>, BlockPos>> out = new ArrayList<>();
		for (int z = c.z() - chunkRadius; z <= c.z() + chunkRadius; z++) {
			for (int x = c.x() - chunkRadius; x <= c.x() + chunkRadius; x++) {
				for (int sy = minY; sy <= maxY; sy++) {
					Optional<?> section = storage.cadence$getOrLoad(SectionPos.asLong(x, sy, z));
					if (section.isEmpty()) continue;
					for (Map.Entry<Holder<PoiType>, Set<PoiRecord>> e : ((PoiSectionAccessor) section.get()).cadence$byType().entrySet()) {
						if (!predicate.test(e.getKey())) continue;
						for (PoiRecord r : e.getValue()) {
							if (!occupied.test(r)) continue;
							BlockPos pos = r.getPos();
							if (Math.abs(pos.getX() - center.getX()) > radius || Math.abs(pos.getZ() - center.getZ()) > radius) continue;
							if (pos.distSqr(center) > radiusSqr) continue;
							if (!filter.test(pos)) continue;
							out.add(Pair.of(r.getPoiType(), pos));
						}
					}
				}
			}
		}
		out.sort(Comparator.comparingDouble(p -> p.getSecond().distSqr(center)));
		if (ChunkOpt.STATS && !ChunkOpt.POI_VERIFY) ChunkOpt.count("poi.scans");
		return out.stream();
	}
}
