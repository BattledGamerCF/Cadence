package dev.cadence.shaders.cadence.mesh;

/**
 * Cadence: vanilla's per-thread meshing light cache (BlockModelLighter.Cache) keeps 100 positions in linked hash maps, so
 * while a section is meshed its light and shade values are looked up, evicted and recomputed over and over (light lookups ~11%
 * of meshing in profiles). Here each meshing thread also has flat arrays for the section being meshed and a one-block border
 * (18x18x18), each slot tagged with the build it was filled in, so nothing is ever cleared: a value is computed once per build
 * exactly as vanilla computes it (BrightnessGetter.DEFAULT / BlockState.getShadeBrightness) and reused. Positions outside the
 * window, and any use of the cache outside a section build, go through vanilla's maps as before. Within a build the result is
 * what vanilla's cache gives when nothing is evicted. -Dcadence.meshLight=false: off.
 */
public final class MeshLightCache {
	public static volatile boolean enabled = !"false".equals(System.getProperty("cadence.meshLight"));
	public static final int SPAN = 18;
	/** The section being meshed on this thread (SectionCompiler.compile HEAD), for the cache's enable() that follows. */
	private static final ThreadLocal<int[]> PENDING = ThreadLocal.withInitial(() -> new int[4]);

	private MeshLightCache() {
	}

	/** SectionCompiler.compile HEAD: the section's minimum block corner. */
	public static void begin(int x, int y, int z) {
		int[] p = PENDING.get();
		p[0] = x;
		p[1] = y;
		p[2] = z;
		p[3] = enabled ? 1 : 0;
	}

	/** BlockModelLighter.Cache.enable(): the pending section, once (null when none or off). */
	public static int[] take() {
		int[] p = PENDING.get();
		if (p[3] == 0) return null;
		p[3] = 0;
		return p;
	}
}
