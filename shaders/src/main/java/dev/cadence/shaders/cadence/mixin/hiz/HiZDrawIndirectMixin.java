package dev.cadence.shaders.cadence.mixin.hiz;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.cadence.shaders.cadence.hiz.DrawIndirectAccess;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Cadence (HiZ): read access to an indirect view's draw groups and section infos. */
@Mixin(ChunkSectionsToRender.DrawIndirect.class)
public abstract class HiZDrawIndirectMixin implements DrawIndirectAccess {
	@Shadow @Final private EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> drawGroupsPerLayer;
	@Shadow @Final private GpuBufferSlice chunkSectionInfos;

	@Override
	public Map<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> cadence$groups() {
		return drawGroupsPerLayer;
	}

	@Override
	public GpuBufferSlice cadence$infos() {
		return chunkSectionInfos;
	}
}
