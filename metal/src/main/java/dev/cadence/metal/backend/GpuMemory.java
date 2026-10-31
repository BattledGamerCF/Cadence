package dev.cadence.metal.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;

/** Cadence: the Metal device's currently allocated bytes (MTLDevice.currentAllocatedSize), and what holds them, for diagnostics. */
public final class GpuMemory {
	static final java.util.Set<MetalTexture> LIVE_TEXTURES = java.util.concurrent.ConcurrentHashMap.newKeySet();
	static final java.util.Set<MetalBuffer> LIVE_BUFFERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private GpuMemory() {}

	/**
	 * Live textures and buffers grouped by label (digits folded to #), the largest groups first: "label count×MB". Texture
	 * bytes are computed from size, format and mips (Metal may pad); buffers count rename() spares too.
	 */
	public static String census(int groups) {
		java.util.Map<String, long[]> by = new java.util.HashMap<>();
		long textures = 0, buffers = 0;
		for (MetalTexture t : LIVE_TEXTURES) {
			long bytes = 0;
			for (int m = 0; m < t.getMipLevels(); m++)
				bytes += (long) t.getWidth(m) * t.getHeight(m) * t.getFormat().blockSize() * Math.max(1, t.getDepthOrLayers());
			textures += bytes;
			String key = "T " + t.getLabel().replaceAll("[0-9]+", "#") + " " + t.getWidth(0) + "x" + t.getHeight(0) + " " + t.getFormat();
			long[] v = by.computeIfAbsent(key, k -> new long[2]);
			v[0]++;
			v[1] += bytes;
		}
		for (MetalBuffer b : LIVE_BUFFERS) {
			long bytes = b.size() + b.spareBytes();
			buffers += bytes;
			String label;
			try {
				label = b.label == null ? "?" : b.label.get();
			} catch (RuntimeException e) {
				label = "?";
			}
			String key = "B " + (label == null ? "?" : label.replaceAll("[0-9]+", "#"));
			long[] v = by.computeIfAbsent(key, k -> new long[2]);
			v[0]++;
			v[1] += bytes;
		}
		StringBuilder out = new StringBuilder(String.format(java.util.Locale.ROOT, "textures %d MB, buffers %d MB:", textures >> 20, buffers >> 20));
		by.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(groups)
			.forEach(e -> out.append(String.format(java.util.Locale.ROOT, " [%s %d×%.1f MB]", e.getKey(), e.getValue()[0], e.getValue()[1] / 1048576.0)));
		return out.toString();
	}

	/** -1 when not on Cadence's Metal backend. Render thread. */
	public static long allocatedBytes() {
		var device = RenderSystem.getDevice();
		if (device == null) return -1;
		long ctx = ((FrontendCommandEncoder) device.createCommandEncoder()).backend() instanceof MetalEncoder me ? me.ctx : 0;
		return ctx != 0 ? Native.deviceAllocated(ctx) : -1;
	}
}
