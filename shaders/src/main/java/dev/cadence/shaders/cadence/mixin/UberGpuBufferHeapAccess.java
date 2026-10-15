package dev.cadence.shaders.cadence.mixin;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (ChunkHeapCompactor): a heap's GPU buffer. */
@Mixin(UberGpuBuffer.UberGpuBufferHeap.class)
public interface UberGpuBufferHeapAccess {
	@Accessor("gpuBuffer")
	GpuBuffer cadence$gpuBuffer();
}
