// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * C3: the section map of a published light snapshot (LightSnapshots): lookups go to the delta, then to the shared base. A
 * snapshot's map is only ever read through get/containsKey (DataLayerStorageMap.getLayer/hasLayer with the cache disabled), so
 * everything else is refused rather than answered wrongly.
 */
public final class LayeredLightMap extends Long2ObjectOpenHashMap<DataLayer> {
	public Long2ObjectOpenHashMap<DataLayer> base, delta;

	public LayeredLightMap() {
		super(0);
	}

	@Override
	public DataLayer get(long k) {
		DataLayer d = this.delta.get(k);
		if (d != null) return d == LightSnapshots.REMOVED ? null : d;
		return this.base.get(k);
	}

	@Override
	public boolean containsKey(long k) {
		return this.get(k) != null;
	}

	@Override
	public DataLayer put(long k, DataLayer v) {
		throw new UnsupportedOperationException("light snapshot is read-only");
	}

	@Override
	public DataLayer remove(long k) {
		throw new UnsupportedOperationException("light snapshot is read-only");
	}

	@Override
	public int size() {
		throw new UnsupportedOperationException("light snapshot has no size");
	}

	@Override
	public LayeredLightMap clone() {
		throw new UnsupportedOperationException("light snapshot is not copied");
	}
}
