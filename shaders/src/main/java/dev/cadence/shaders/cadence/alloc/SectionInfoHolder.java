package dev.cadence.shaders.cadence.alloc;

import net.minecraft.client.renderer.DynamicGpuData;

/** Cadence (SectionInfoReuseMixin): a render section's last instance record and unpacked draw records. Implemented by SectionInfoHolderMixin. */
public interface SectionInfoHolder {
	DynamicGpuData.ChunkSectionInfo cadence$lastInfo();

	void cadence$setLastInfo(DynamicGpuData.ChunkSectionInfo info);

	/** The section's last two unpacked (translucent) draw records, most recent first. */
	DynamicGpuData.IndexedDraw[] cadence$lastDraws();
}
