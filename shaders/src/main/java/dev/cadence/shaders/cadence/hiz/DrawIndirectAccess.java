package dev.cadence.shaders.cadence.hiz;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;

/** Cadence (HiZ): what a prepared indirect view draws, implemented on ChunkSectionsToRender.DrawIndirect by a mixin. */
public interface DrawIndirectAccess {
	Map<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> cadence$groups();

	GpuBufferSlice cadence$infos();
}
