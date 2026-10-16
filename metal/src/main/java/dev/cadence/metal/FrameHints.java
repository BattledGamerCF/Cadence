package dev.cadence.metal;

import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.function.Supplier;

/**
 * Cadence: frame-end hints for callers outside this package. depthDiscard: at the end of each presented frame the main
 * render target's depth (and {@link #extraDepth}, e.g. the hand's depth) is not written back to memory; vanilla clears
 * both before anything reads them again, so the store is pure bandwidth (21 MB a frame each at 2940x1846).
 */
public final class FrameHints {
	public static volatile boolean depthDiscard = Boolean.getBoolean("cadence.metal.frameDepthDiscard");
	public static volatile Supplier<GpuTexture> extraDepth;

	private FrameHints() {
	}

	/** Lazy clears (MetalEncoder.LAZY_CLEARS), switchable at run time for in-run A/B tests. */
	public static void lazyClears(boolean on) {
		MetalEncoder.LAZY_CLEARS = on;
	}

	public static boolean lazyClears() {
		return MetalEncoder.LAZY_CLEARS;
	}

	/** Measurement only (self-test ab=drop-NAME): draws whose pipeline name contains this are skipped; null: none. */
	public static volatile String dropPipeline;
}
