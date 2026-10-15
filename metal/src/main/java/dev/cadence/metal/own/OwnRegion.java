// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * -Dcadence.own.mesh.region=true: the section's neighbourhood as vanilla's renderers see it, cached for one compile. Block states
 * within 2 blocks of the section (the farthest any face cull, AO corner or fluid height looks) and sky / block light within 1
 * block are read from vanilla's RenderSectionRegion and the live light engine once per position, then served from flat arrays
 * (generation-stamped, so nothing is cleared between compiles). Values are what vanilla reads at that moment: identical output
 * for a scene whose light doesn't change mid-compile (vanilla's own 100-entry light cache makes the same assumption).
 */
public final class OwnRegion implements BlockAndTintGetter {
	private static final int S = 20, L = 18;
	private static final ThreadLocal<OwnRegion> LOCAL = ThreadLocal.withInitial(OwnRegion::new);

	// per-compile caches, cleared to their "unknown" sentinel at each compile (null, -1, NaN): a hit is one load
	private final BlockState[] states = new BlockState[S * S * S];
	private final byte[] sky = new byte[L * L * L], block = new byte[L * L * L];
	private final int[] packed = new int[L * L * L];
	private final float[] shade = new float[S * S * S];
	/**
	 * -Dcadence.own.mesh.aocache=true: per position, its AO sample as one memo: light coords with its own state (lightAt(stateAt(p),
	 * p)), shade with its own state, light permeability of its state. -1 / NaN / -1 = not yet known.
	 */
	static final boolean AOCACHE = Boolean.getBoolean("cadence.own.mesh.aocache");
	/**
	 * -Dcadence.own.mesh.frapiRegion=true: during our compile, vanilla's RenderSectionRegion (which Fabric API's renderer gets, as
	 * Fabric API extends that class) answers getBlockState / getFluidState from this cache (OwnMeshRegionStateMixin), and the
	 * lighter's cache hook accepts it too. Misses read the region's section copies directly (no recursion through the hook).
	 */
	public static final boolean FRAPI_REGION = Boolean.getBoolean("cadence.own.mesh.frapiRegion");
	private SectionCopy[] copies;
	private int minSx, minSy, minSz;
	final int[] aoLight = new int[S * S * S];
	final float[] aoShade = new float[S * S * S];
	final byte[] aoPerm = new byte[S * S * S];
	private int gen;
	private RenderSectionRegion region;
	private LevelLightEngine light;
	private int sx, sy, sz, lx, ly, lz;
	/** The OwnRegion of the compile running on this thread (ClientLevel.calculateBlockTint's hook asks), else null. */
	private static final ThreadLocal<OwnRegion> CURRENT = new ThreadLocal<>();
	/** -Dcadence.own.mesh.tint=true: biome blends from per-compile biome and resolver-colour arrays (OwnMeshTintMixin). */
	static final boolean TINT = Boolean.getBoolean("cadence.own.mesh.tint");
	/** -Dcadence.own.mesh.prefill=true: the section's own 4096 states unpacked from its palette at once, not read one by one. */
	static final boolean PREFILL = Boolean.getBoolean("cadence.own.mesh.prefill");
	// tint box: x, z in [origin - 1 - 7, origin + 16 + 7], y in [origin - 1, origin + 16] (the largest blend radius, 7)
	private static final int TW = 32, TH = 18, TR = 8;
	@SuppressWarnings("unchecked")
	private final Holder<Biome>[] biomes = new Holder[TW * TW * TH];
	private final int[] biomeGen = new int[TW * TW * TH];
	/** A biome was cached in this compile (release() then clears the slots: see there). */
	private boolean biomesUsed;
	private final ColorResolver[] resolvers = new ColorResolver[4];
	private final int[][] colors = new int[4][TW * TW * TH], colorGen = new int[4][TW * TW * TH];
	private final BlockPos.MutableBlockPos tintPos = new BlockPos.MutableBlockPos();
	private int tx, ty, tz;
	private final int[] ids = new int[4096];

	static OwnRegion of(RenderSectionRegion region, BlockPos origin) {
		OwnRegion r = LOCAL.get();
		java.util.Arrays.fill(r.states, null);
		java.util.Arrays.fill(r.sky, (byte) -1);
		java.util.Arrays.fill(r.block, (byte) -1);
		java.util.Arrays.fill(r.packed, -1);
		java.util.Arrays.fill(r.shade, Float.NaN);
		if (AOCACHE) {
			java.util.Arrays.fill(r.aoLight, -1);
			java.util.Arrays.fill(r.aoShade, Float.NaN);
			java.util.Arrays.fill(r.aoPerm, (byte) -1);
		}
		if (++r.gen == 0) {
			java.util.Arrays.fill(r.biomeGen, 0);
			for (int[] cg : r.colorGen) java.util.Arrays.fill(cg, 0);
			r.gen = 1;
		}
		r.region = region;
		if (FRAPI_REGION) {
			r.copies = ((OwnRegionAccess) region).cadence$sections();
			r.minSx = (origin.getX() >> 4) - 1;
			r.minSy = (origin.getY() >> 4) - 1;
			r.minSz = (origin.getZ() >> 4) - 1;
			((OwnRegionAccess) region).cadence$own(r);  // a field on the region (a thread-local lookup cost more than the cache saved)
		}
		r.light = region.getLightEngine();
		r.sx = origin.getX() - 2;
		r.sy = origin.getY() - 2;
		r.sz = origin.getZ() - 2;
		r.lx = origin.getX() - 1;
		r.ly = origin.getY() - 1;
		r.lz = origin.getZ() - 1;
		r.tx = origin.getX() - TR;
		r.ty = origin.getY() - 1;
		r.tz = origin.getZ() - TR;
		if (PREFILL) r.prefill(region);
		if (TINT) CURRENT.set(r);
		return r;
	}

	/** Drops the region reference (the section copies) after the compile. */
	void release() {
		if (FRAPI_REGION) {
			if (this.region != null) ((OwnRegionAccess) this.region).cadence$own(null);
			this.copies = null;
		}
		this.region = null;
		this.light = null;
		// the biome holders reach their level's biome registry: slots a later compile (or world) doesn't overwrite would keep an older
		// world's registry alive through this thread-local (~0.57 MB a join). Stale slots are never read (biomeGen stamps), so
		// clearing them changes nothing drawn.
		if (this.biomesUsed) {
			java.util.Arrays.fill(this.biomes, null);
			this.biomesUsed = false;
		}
		if (TINT) CURRENT.remove();
	}

	private static final ThreadLocal<boolean[]> SUSPENDED = ThreadLocal.withInitial(() -> new boolean[1]);

	static @Nullable OwnRegion current() {
		return SUSPENDED.get()[0] ? null : CURRENT.get();
	}

	/** Verify: vanilla's blend runs with the hook off. */
	static void suspend(boolean on) {
		SUSPENDED.get()[0] = on;
	}

	/** The section's 16^3 states (region index 13) into the state array, from one unpack of its palette storage. */
	private void prefill(RenderSectionRegion region) {
		SectionCopy copy = ((OwnRegionAccess) region).cadence$sections()[13];
		OwnRegionAccess.Copy c = (OwnRegionAccess.Copy) copy;
		if (c.cadence$debug()) return;
		PalettedContainer<BlockState> section = c.cadence$section();
		int[] ids = this.ids;
		BlockState[] states = this.states;
		if (section == null) {
			BlockState air = Blocks.AIR.defaultBlockState();
			for (int i = 0; i < 4096; i++) {
				int at = ((i >> 8) + 2) * S * S + ((i >> 4 & 15) + 2) * S + (i & 15) + 2;
				states[at] = air;
			}
			return;
		}
		Palette<BlockState> palette = OwnPalette.unpack(section, ids);
		BlockState last = null;
		int lastId = -1;
		for (int i = 0; i < 4096; i++) {
			int id = ids[i];
			if (id != lastId) {
				last = palette.valueFor(id);
				lastId = id;
			}
			int at = ((i >> 8) + 2) * S * S + ((i >> 4 & 15) + 2) * S + (i & 15) + 2;
			states[at] = last;
		}
	}

	/**
	 * ClientLevel.calculateBlockTint(pos, resolver) for a blend radius > 0 whose window lies in the tint box: the same integer
	 * sums over the same biomes and resolver colours, each biome and colour computed once per compile. Null: vanilla computes.
	 */
	@Nullable Integer blendTint(ClientLevel level, BlockPos pos, ColorResolver resolver, int dist) {
		int x0 = pos.getX() - dist - this.tx, z0 = pos.getZ() - dist - this.tz, y = pos.getY() - this.ty;
		int w = dist * 2 + 1;
		if (x0 < 0 || z0 < 0 || y < 0 || y >= TH || x0 + w > TW || z0 + w > TW) return null;
		int slot = -1;
		for (int i = 0; i < 4; i++) {
			if (this.resolvers[i] == resolver) {
				slot = i;
				break;
			}
			if (this.resolvers[i] == null) {
				this.resolvers[i] = resolver;
				slot = i;
				break;
			}
		}
		if (slot < 0) return null;
		int[] col = this.colors[slot], cg = this.colorGen[slot];
		int g = this.gen, totalRed = 0, totalGreen = 0, totalBlue = 0;
		for (int dz = 0; dz < w; dz++) {
			for (int dx = 0; dx < w; dx++) {
				int i = (y * TW + z0 + dz) * TW + x0 + dx;
				int color;
				if (cg[i] == g) {
					color = col[i];
				} else {
					int bx = this.tx + x0 + dx, bz = this.tz + z0 + dz;
					Holder<Biome> biome;
					if (this.biomeGen[i] == g) {
						biome = this.biomes[i];
					} else {
						biome = level.getBiome(this.tintPos.set(bx, pos.getY(), bz));
						this.biomes[i] = biome;
						this.biomeGen[i] = g;
						this.biomesUsed = true;
					}
					color = resolver.getColor(biome.value(), bx, bz);
					col[i] = color;
					cg[i] = g;
				}
				totalRed += ARGB.red(color);
				totalGreen += ARGB.green(color);
				totalBlue += ARGB.blue(color);
			}
		}
		int count = w * w;
		return ARGB.color(totalRed / count, totalGreen / count, totalBlue / count);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		int x = pos.getX() - this.sx, y = pos.getY() - this.sy, z = pos.getZ() - this.sz;
		if ((x | y | z) >= 0 && x < S && y < S && z < S) {
			int i = (y * S + z) * S + x;
			BlockState s = this.states[i];
			if (s != null) return s;
			s = this.raw(pos);
			this.states[i] = s;
			return s;
		}
		return this.raw(pos);
	}

	/** RenderSectionRegion.getBlockState's own body (section copy by section coordinates), bypassing the cache hook. */
	private BlockState raw(BlockPos pos) {
		if (!FRAPI_REGION) return this.region.getBlockState(pos);
		int sx = pos.getX() >> 4, sy = pos.getY() >> 4, sz = pos.getZ() >> 4;
		return this.copies[sx - this.minSx + (sy - this.minSy) * 3 + (sz - this.minSz) * 9].getBlockState(pos);
	}

	/** FRAPI_REGION: this thread's compile cache if r is its region, else null. */
	public static @Nullable OwnRegion forRegion(Object r) {
		return r instanceof OwnRegionAccess a ? a.cadence$own() : null;
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return this.getBlockState(pos).getFluidState();
	}

	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		int x = pos.getX() - this.lx, y = pos.getY() - this.ly, z = pos.getZ() - this.lz;
		if ((x | y | z) >= 0 && x < L && y < L && z < L) {
			int i = (y * L + z) * L + x;
			if (layer == LightLayer.SKY) {
				int c = this.sky[i];
				if (c >= 0) return c;
				int v = this.light.getLayerListener(LightLayer.SKY).getLightValue(pos);
				this.sky[i] = (byte) v;
				return v;
			}
			int c = this.block[i];
			if (c >= 0) return c;
			int v = this.light.getLayerListener(LightLayer.BLOCK).getLightValue(pos);
			this.block[i] = (byte) v;
			return v;
		}
		return this.light.getLayerListener(layer).getLightValue(pos);
	}

	/**
	 * BlockModelLighter.Cache.getLightCoords for this region: LightCoordsUtil.getLightCoords with the packed brightness (as
	 * BrightnessGetter.DEFAULT packs it) cached per position instead of in the 100-entry hash map.
	 */
	public int lightCoords(BlockState state, BlockPos pos) {
		if (state.emissiveRendering()) return 15728880;
		int x = pos.getX() - this.lx, y = pos.getY() - this.ly, z = pos.getZ() - this.lz;
		int p;
		if ((x | y | z) >= 0 && x < L && y < L && z < L) {
			int i = (y * L + z) * L + x;
			p = this.packed[i];
			if (p < 0) {
				p = LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(this, pos);
				this.packed[i] = p;
			}
		} else {
			p = LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(this, pos);
		}
		int blockLight = LightCoordsUtil.block(p), emission = state.getLightEmission();
		return blockLight < emission ? LightCoordsUtil.withBlock(p, emission) : p;
	}

	/** BlockModelLighter.Cache.getShadeBrightness for this region: cached per position, as vanilla's cache keys it. */
	public float shadeBrightness(BlockState state, BlockPos pos) {
		int x = pos.getX() - this.sx, y = pos.getY() - this.sy, z = pos.getZ() - this.sz;
		if ((x | y | z) >= 0 && x < S && y < S && z < S) {
			int i = (y * S + z) * S + x;
			float v = this.shade[i];
			if (v == v) return v;
			v = state.getShadeBrightness(this, pos);
			this.shade[i] = v;
			return v;
		}
		return state.getShadeBrightness(this, pos);
	}

	// ---- int-coordinate forms (OwnBlockRenderer): the same caches, no BlockPos on a hit ----
	private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

	/** AOCACHE: the 20^3 index of p, or -1 outside (callers then use stateAt / lightAt / shadeAt). */
	int aoIndex(int px, int py, int pz) {
		int x = px - this.sx, y = py - this.sy, z = pz - this.sz;
		return (x | y | z) >= 0 && x < S && y < S && z < S ? (y * S + z) * S + x : -1;
	}

	/** AOCACHE: lightAt(stateAt(p), p), memoized at index i (i = aoIndex(p) >= 0). */
	int aoLight(int i, int px, int py, int pz) {
		int v = this.aoLight[i];
		if (v >= 0) return v;
		v = this.lightAt(this.stateAt(px, py, pz), px, py, pz);
		this.aoLight[i] = v;
		return v;
	}

	/** AOCACHE: shadeAt(stateAt(p), p), memoized at index i. */
	float aoShade(int i, int px, int py, int pz) {
		float v = this.aoShade[i];
		if (v == v) return v;
		v = this.shadeAt(this.stateAt(px, py, pz), px, py, pz);
		this.aoShade[i] = v;
		return v;
	}

	/** AOCACHE: stateAt(p).isLightPermeable(), memoized at index i. */
	boolean aoPerm(int i, int px, int py, int pz) {
		int v = this.aoPerm[i];
		if (v >= 0) return v != 0;
		boolean b = this.stateAt(px, py, pz).isLightPermeable();
		this.aoPerm[i] = (byte) (b ? 1 : 0);
		return b;
	}

	BlockState stateAt(int px, int py, int pz) {
		int x = px - this.sx, y = py - this.sy, z = pz - this.sz;
		if ((x | y | z) >= 0 && x < S && y < S && z < S) {
			int i = (y * S + z) * S + x;
			BlockState s = this.states[i];
			if (s != null) return s;
			s = this.raw(this.scratch.set(px, py, pz));
			this.states[i] = s;
			return s;
		}
		return this.raw(this.scratch.set(px, py, pz));
	}

	int lightAt(BlockState state, int px, int py, int pz) {
		if (state.emissiveRendering()) return 15728880;
		int x = px - this.lx, y = py - this.ly, z = pz - this.lz;
		int p;
		if ((x | y | z) >= 0 && x < L && y < L && z < L) {
			int i = (y * L + z) * L + x;
			p = this.packed[i];
			if (p < 0) {
				p = LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(this, this.scratch.set(px, py, pz));
				this.packed[i] = p;
			}
		} else {
			p = LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(this, this.scratch.set(px, py, pz));
		}
		int blockLight = LightCoordsUtil.block(p), emission = state.getLightEmission();
		return blockLight < emission ? LightCoordsUtil.withBlock(p, emission) : p;
	}

	float shadeAt(BlockState state, int px, int py, int pz) {
		int x = px - this.sx, y = py - this.sy, z = pz - this.sz;
		if ((x | y | z) >= 0 && x < S && y < S && z < S) {
			int i = (y * S + z) * S + x;
			float v = this.shade[i];
			if (v == v) return v;
			v = state.getShadeBrightness(this, this.scratch.set(px, py, pz));
			this.shade[i] = v;
			return v;
		}
		return state.getShadeBrightness(this, this.scratch.set(px, py, pz));
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return this.region.getBlockEntity(pos);
	}

	@Override
	public CardinalLighting cardinalLighting() {
		return this.region.cardinalLighting();
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return this.light;
	}

	@Override
	public int getBlockTint(BlockPos pos, ColorResolver resolver) {
		return this.region.getBlockTint(pos, resolver);
	}

	@Override
	public int getMinY() {
		return this.region.getMinY();
	}

	@Override
	public int getHeight() {
		return this.region.getHeight();
	}
}
