package dev.cadence.shaders.cadence.render;

/**
 * Cadence: the size of the GPU heaps vanilla's chunk uber buffers grow by, chosen when the buffers are created (world load,
 * and every shader on/off switch: ChunkBufferRebuild).
 *
 * <p>Vanilla adds whole 128 MB vertex heaps and 32 MB index heaps per layer; Cadence makes a buffer resident when the GPU first
 * uses it, so the unused part of each layer's last heap is real memory. With a shader pack (BSL, RD 16) 32 MB heaps took the
 * Metal total from 1,192 to 878 MB at the same frame rate (GPU-bound). Without a pack, small heaps once cost ~20% of the
 * uncapped frame rate (more draw groups); after the draw-list work (PackedDraws etc.) 32 MB measured within run-to-run noise
 * of 128 MB (RD 16: 269/241/133 vs 257/230/128 fps) for 150 MB less Metal memory (689 -> 539 MB), so both use 32 MB now.
 * -Dcadence.chunkHeapMB=N (with a pack) and -Dcadence.vanillaChunkHeapMB=N (without): 8..128, 128 = vanilla's.
 */
public final class ChunkHeaps {
	private static final int PACK_MB = Math.max(8, Math.min(128, Integer.getInteger("cadence.chunkHeapMB", 32)));
	private static final int VANILLA_MB = Math.max(8, Math.min(128, Integer.getInteger("cadence.vanillaChunkHeapMB", 32)));

	private ChunkHeaps() {
	}

	public static int vertexBytes() {
		return (packActive() ? PACK_MB : VANILLA_MB) << 20;
	}

	public static int indexBytes() {
		return (packActive() ? Math.max(4, PACK_MB / 4) : Math.max(4, VANILLA_MB / 4)) << 20;
	}

	private static boolean packActive() {
		return dev.cadence.shaders.geometry.TerrainShaderGeometry.isEnabled();
	}
}
