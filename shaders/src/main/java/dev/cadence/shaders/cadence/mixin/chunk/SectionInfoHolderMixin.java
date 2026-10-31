package dev.cadence.shaders.cadence.mixin.chunk;

import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import dev.cadence.shaders.cadence.alloc.SectionInfoHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Cadence (SectionInfoReuseMixin): the record field on each render section. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class SectionInfoHolderMixin implements SectionInfoHolder {
	@Unique private DynamicGpuData.ChunkSectionInfo cadence$lastInfo;
	@Unique private final DynamicGpuData.IndexedDraw[] cadence$lastDraws = new DynamicGpuData.IndexedDraw[2];

	@Override
	public DynamicGpuData.IndexedDraw[] cadence$lastDraws() {
		return cadence$lastDraws;
	}

	@Override
	public DynamicGpuData.ChunkSectionInfo cadence$lastInfo() {
		return cadence$lastInfo;
	}

	@Override
	public void cadence$setLastInfo(DynamicGpuData.ChunkSectionInfo info) {
		cadence$lastInfo = info;
	}
}
