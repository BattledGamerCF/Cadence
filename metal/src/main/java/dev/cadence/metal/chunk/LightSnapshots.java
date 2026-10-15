// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.chunk;

import net.minecraft.world.level.chunk.DataLayer;

/**
 * C3 (-Dcadence.chunk.light=true|verify): light engines publish a snapshot of their section map (section -> DataLayer) after every
 * update batch that changed a section (LayerLightSectionStorage.swapSectionMap); vanilla clones the whole hash map each time
 * (~0.5-0.8 MB at render distance 16, tens of times a second while chunks load). Here a snapshot is a shared, never-modified base
 * plus a small delta of the sections set, replaced or removed since that base, so a swap copies only the delta; when the delta
 * outgrows an eighth of the base, the next snapshot starts a new base (a full clone, as vanilla). A snapshot answers every
 * lookup with the DataLayer object vanilla's clone would hold: the updating map's mutations all go through setLayer,
 * removeLayer and copyDataLayer, which record their keys, and the delta takes the updating map's value for each recorded key
 * at the moment of the swap. verify builds vanilla's clone at every swap and checks every key against the snapshot.
 */
public interface LightSnapshots {
	/** Marks a removed section in a delta (never returned). */
	DataLayer REMOVED = new DataLayer();

	/** Called on the updating map from inside copy(): fill the new (snapshot) map's base and delta. */
	void cadence$snapshotInto(Object snapshot);
}
