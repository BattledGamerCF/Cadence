package dev.cadence.shaders.cadence.mesh;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Cadence: biome colour blending (grass, leaves, water) in chunk meshing. For each tinted block vanilla's
 * ClientLevel.calculateBlockTint looks up the biome at every position of a (2r+1)² square around it (25 lookups at the default
 * blend radius 2), each a chunk lookup plus a biome palette read, and the squares of neighbouring blocks overlap almost
 * entirely (~18% of meshing in profiles once block and light reads were cached). During a section build the biome found at each
 * position is remembered for that build (the section ±(1 + 7) horizontally, the widest blend, ±1 vertically), each slot tagged
 * with the build, so a position's biome is looked up once per build. Exact: the same Holder vanilla's lookup returns; the
 * colours, sums and the division are vanilla's own code. Outside a build, or outside the window, vanilla's lookup as before.
 * -Dcadence.meshTint=false: off.
 */
public final class MeshTintCache {
	public static volatile boolean enabled = !"false".equals(System.getProperty("cadence.meshTint"));
	static final int H = 16 + 2 * (1 + 7), V = 18, N = H * H * V;
	private static final ThreadLocal<MeshTintCache> STATE = ThreadLocal.withInitial(MeshTintCache::new);

	@SuppressWarnings("unchecked")
	private final Holder<Biome>[] biomes = new Holder[N];
	private final int[] gens = new int[N];
	private int gen, ox, oy, oz;
	private boolean active;

	private MeshTintCache() {
	}

	/** SectionCompiler.compile HEAD (worker): the section about to be meshed. */
	public static void begin(int x, int y, int z) {
		MeshTintCache s = STATE.get();
		s.active = enabled;
		if (!s.active) return;
		if (++s.gen == 0) {
			java.util.Arrays.fill(s.gens, 0);
			s.gen = 1;
		}
		s.ox = x - 8;
		s.oy = y - 1;
		s.oz = z - 8;
	}

	/** SectionCompiler.compile RETURN. */
	public static void end() {
		STATE.get().active = false;
	}

	/** The cached state for this thread when a build is running, else null (the caller then asks the level). */
	public static MeshTintCache current() {
		MeshTintCache s = STATE.get();
		return s.active ? s : null;
	}

	/** The slot for (x, y, z), or -1 outside the window. */
	public int index(int x, int y, int z) {
		x -= ox;
		y -= oy;
		z -= oz;
		if ((x | y | z) < 0 || x >= H || y >= V || z >= H) return -1;
		return (y * H + z) * H + x;
	}

	public Holder<Biome> get(int i) {
		return gens[i] == gen ? biomes[i] : null;
	}

	public void put(int i, Holder<Biome> biome) {
		biomes[i] = biome;
		gens[i] = gen;
	}
}
