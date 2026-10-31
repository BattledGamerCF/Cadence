package dev.cadence.shaders.cadence.mixin.shadow;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (ShadowProxies): the terrain transform a prepared view draws with, for the proxies drawn into the same pass. */
@Mixin(ChunkSectionsToRender.class)
public interface ChunkSectionsToRenderAccessor {
	@Accessor("terrainTransformUBO")
	GpuBufferSlice cadence$terrainTransform();
}
