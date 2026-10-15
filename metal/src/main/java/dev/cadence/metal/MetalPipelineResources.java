package dev.cadence.metal;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only reflection for the shader loader (shaders/, adapted from minecraft-metal-renderer, MIT): the names of the
 * resources a compiled pipeline's shaders actually use, so the loader can skip producing ones nothing reads. Empty
 * means "unknown": the loader then keeps every declared dependency, which is always correct.
 */
public final class MetalPipelineResources {
	private MetalPipelineResources() {
	}

	public static Optional<Set<String>> activeResourceNames(CompiledRenderPipeline pipeline) {
		return Optional.empty();
	}
}
