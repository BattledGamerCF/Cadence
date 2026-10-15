package dev.cadence.shaders.cadence.mixin;

import java.util.Map;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (ChunkHeapCompactor): the dispatcher's per-layer uber buffers. */
@Mixin(SectionRenderDispatcher.class)
public interface ChunkHeapCompactStepMixin {
	@Accessor("chunkUberBuffers")
	Map<?, ?> cadence$uberBuffers();
}
