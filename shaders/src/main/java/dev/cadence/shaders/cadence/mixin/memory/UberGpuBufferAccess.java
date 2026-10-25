package dev.cadence.shaders.cadence.mixin.memory;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (ChunkHeapCompactor): vanilla's chunk uber buffer internals. */
@Mixin(UberGpuBuffer.class)
public interface UberGpuBufferAccess {
	@Accessor("nodes")
	List<com.mojang.datafixers.util.Pair<com.mojang.blaze3d.vertex.TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap>> cadence$nodes();

	@Accessor("allocationMap")
	Map<Object, com.mojang.blaze3d.vertex.TlsfAllocator.Allocation> cadence$allocations();

	@Accessor("stagedAllocations")
	Object2ObjectOpenHashMap<Object, ?> cadence$staged();

	@Accessor("heapSize")
	int cadence$heapSize();

	@Accessor("alignSize")
	int cadence$alignSize();
}
