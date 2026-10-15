package dev.cadence.shaders.cadence.mixin;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (ChunkHeapCompactor): one layer's vertex and index uber buffers. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$SectionUberBuffers")
public interface SectionUberBuffersAccess {
	@Accessor("vertexBuffer")
	UberGpuBuffer<?> cadence$vertex();

	@Accessor("indexBuffer")
	UberGpuBuffer<?> cadence$index();
}
