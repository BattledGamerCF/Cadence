package dev.cadence.metal.backend;

import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.Optional;

/**
 * The entry point the shader loader (shaders/, adapted from minecraft-metal-renderer, MIT) uses for hardware depth
 * comparison: a sampler that compares a shadow map's depth with the reference in the sampler itself (less-or-equal),
 * instead of the pack's shader reading depth and comparing by hand. Renderpearl's samplers have no compare function,
 * so this is a backend extension. It is only used once the device advertises {@link #CAPABILITY}; until then the
 * loader emulates the comparison in its shaders.
 */
public final class MetalGpuSampler {
	public static final String CAPABILITY = "depth-comparison-lequal";
	/** -Dcadence.cadence.hwShadowCompare=false: don't advertise it (the loader then emulates), for A/B runs. */
	static final boolean ENABLED = !"false".equals(System.getProperty("cadence.cadence.hwShadowCompare"));

	private MetalGpuSampler() {
	}

	/** The comparison twin of a linear sampler from this backend, or empty when it can't make one. */
	public static Optional<GpuSampler> comparisonVariant(GpuSampler linear) {
		if (!ENABLED || !(linear instanceof MetalSampler metal)) return Optional.empty();
		return Optional.of(metal.comparisonTwin());
	}
}
