package dev.cadence.shaders.cadence.mixin;

import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import dev.cadence.shaders.cadence.memory.ChunkHeapCompactor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Cadence (ChunkHeapCompactor): the heap being emptied takes no new allocations from vanilla's upload. */
@Mixin(UberGpuBuffer.class)
public abstract class ChunkHeapCompactMixin implements ChunkHeapCompactor.Holder {
	@Unique private TlsfAllocator cadence$evacuating, cadence$closing;

	@Redirect(method = "uploadStagedAllocations", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vertex/TlsfAllocator;allocate(JI)Lcom/mojang/blaze3d/vertex/TlsfAllocator$Allocation;"))
	private TlsfAllocator.Allocation cadence$skipEvacuating(TlsfAllocator allocator, long size, int align) {
		if (allocator == cadence$evacuating || allocator == cadence$closing) return null;
		return allocator.allocate(size, align);
	}

	@Override
	public TlsfAllocator cadence$evacuating() {
		return cadence$evacuating;
	}

	@Override
	public void cadence$setEvacuating(TlsfAllocator allocator) {
		cadence$evacuating = allocator;
	}

	@Override
	public TlsfAllocator cadence$closing() {
		return cadence$closing;
	}

	@Override
	public void cadence$setClosing(TlsfAllocator allocator) {
		cadence$closing = allocator;
	}
}
