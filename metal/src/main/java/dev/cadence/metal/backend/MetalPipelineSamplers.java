package dev.cadence.metal.backend;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Cadence: which of a shader pack pipeline's samplers are always sampled with the same sampler state, so that a pass with more
 * than Metal's 16 sampler slots per stage can share slots between them (textures keep a slot each, of 128). The shader loader
 * knows this per pass (colour buffers are always sampled linear and clamped, depth buffers nearest, ...) and states it around
 * the pipeline's compilation on the compiling thread: shader name -> group (equal groups share a slot; a sampler missing from
 * the map gets its own slot). Read by MetalPipeline.packSlots only when a pipeline has more than 16 samplers.
 */
public final class MetalPipelineSamplers {
	private static final ThreadLocal<Map<String, Object>> GROUPS = new ThreadLocal<>();

	private MetalPipelineSamplers() {
	}

	/** States the groups for pipelines compiled on this thread until the returned handle is closed. */
	public static AutoCloseable open(Map<String, Object> groups) {
		Map<String, Object> previous = GROUPS.get();
		GROUPS.set(Map.copyOf(groups));
		return () -> {
			if (previous == null) GROUPS.remove();
			else GROUPS.set(previous);
		};
	}

	static @Nullable Map<String, Object> current() {
		return GROUPS.get();
	}
}
