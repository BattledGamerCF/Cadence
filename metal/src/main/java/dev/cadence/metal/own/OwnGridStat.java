// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;

/**
 * -Dcadence.own.mesh.gridstat=true (measurement only): solid / cutout quads with a vertex coordinate off the compact vertex's 1/2048
 * block grid (fixed((p + 8) * 2048) / 2048 - 8 != p), counted by the block being meshed (OwnMesher sets it per block; fluids: the
 * fluid's block). Logged every 5 s: per layer quads and off-grid quads, and the top blocks.
 */
final class OwnGridStat {
	static final boolean ON = Boolean.getBoolean("cadence.own.mesh.gridstat");
	private static final ThreadLocal<BlockState[]> CURRENT = ThreadLocal.withInitial(() -> new BlockState[1]);
	private static final ThreadLocal<OwnQuads> QUADS = new ThreadLocal<>();
	private static final long[] TOTAL = new long[2], OFF = new long[2];
	private static final Map<String, long[]> BY_BLOCK = new HashMap<>();
	private static long logAt;

	private OwnGridStat() {
	}

	static void block(BlockState state, OwnQuads quads) {
		CURRENT.get()[0] = state;
		QUADS.set(quads);
	}

	/** The solid layer of the quads of the current compile (to tell the layer of a Layer instance). */
	static OwnQuads.Layer solidOf(OwnQuads.Layer l) {
		OwnQuads q = QUADS.get();
		return q == null ? l : q.layers[0];
	}

	static void quad(int layer, float[] p) {
		boolean off = false;
		for (int k = 0; k < 12 && !off; k++) {
			float v = p[k];
			float g = Math.max(0, Math.min(65535, Math.round((v + 8f) * 2048f))) / 2048f - 8f;
			off = g != v;
		}
		BlockState s = CURRENT.get()[0];
		String name = s == null ? "?" : s.getBlock().getDescriptionId();
		synchronized (TOTAL) {
			TOTAL[layer]++;
			if (off) {
				OFF[layer]++;
				BY_BLOCK.computeIfAbsent(name + (layer == 0 ? " (solid)" : " (cutout)"), k -> new long[1])[0]++;
			}
			long now = System.nanoTime();
			if (now > logAt) {
				logAt = now + 5_000_000_000L;
				StringBuilder sb = new StringBuilder(String.format("cadence-own mesh gridstat: solid %d of %d quads off-grid, cutout %d of %d; top:", OFF[0], TOTAL[0], OFF[1], TOTAL[1]));
				BY_BLOCK.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(12)
					.forEach(e -> sb.append(' ').append(e.getKey()).append('=').append(e.getValue()[0]));
				System.out.println(sb);
			}
		}
	}
}
