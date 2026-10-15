// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** -Dcadence.own.int.lists: a layer's vertex uber buffer (SectionRenderDispatcher's private record). */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$SectionUberBuffers")
public interface OwnIntUberBuffersAccess {
	@Accessor("vertexBuffer")
	UberGpuBuffer<?> cadence$vertexBuffer();
}
