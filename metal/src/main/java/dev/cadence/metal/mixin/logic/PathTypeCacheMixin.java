package dev.cadence.metal.mixin.logic;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence Logic {@code pathCache}: a walking mob's path search asks each block's path type through a per-search cache
 * (WalkNodeEvaluator.getCachedPathType): a Long2ObjectOpenHashMap computeIfAbsent with a new capturing lambda per question,
 * cleared when the search is done, and fastutil's clear fills the map's whole arrays, which only grow: after one long search
 * every short one pays for the long one's capacity. In a crowded test scene that cache was ~13% of the server thread. Here
 * the same cache is an open-addressing table whose entries count only with the current search's stamp: done() moves the
 * stamp instead of clearing, and a question allocates nothing. Each position's type is still worked out once per search, by
 * the same getPathTypeOfMob call, so every search finds the same path (FlyNodeEvaluator keeps its own cache, untouched).
 */
@Mixin(WalkNodeEvaluator.class)
abstract class PathTypeCacheMixin extends NodeEvaluator {
	// (made on first use, not in field initializers: Mixin didn't run those for every WalkNodeEvaluator, and a null here
	// crashed the server's first path search)
	@Unique private long[] cadence$keys;
	@Unique private PathType[] cadence$types;
	@Unique private int[] cadence$stamps;
	@Unique private int cadence$stamp, cadence$size;
	/** -Dcadence.logic.pathCacheVerify=true: every cached answer worked out again and compared (counted, logged). */
	@Unique private static final boolean CADENCE_VERIFY = Boolean.getBoolean("cadence.logic.pathCacheVerify");
	@Unique private static long cadence$checked, cadence$wrong, cadence$misses;

	@Unique
	private static void cadence$verify(PathType cached, PathType fresh) {
		if (cached != fresh && cadence$wrong++ < 5) System.out.println("cadence-logic: pathCache MISMATCH " + cached + " vs " + fresh);
		if (++cadence$checked % 200000 == 0 || cadence$checked == 1000 || cadence$checked == 20000) System.out.println("cadence-logic: pathCache verified " + cadence$checked + " hits, " + cadence$wrong + " mismatches");
	}

	/**
	 * @author Cadence
	 * @reason the per-search path type cache without a lambda per question or a full clear per search
	 */
	@Overwrite
	protected PathType getCachedPathType(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		if (this.cadence$keys == null) {
			this.cadence$keys = new long[64];
			this.cadence$types = new PathType[64];
			this.cadence$stamps = new int[64];
			this.cadence$stamp = 1;
		}
		int mask = this.cadence$keys.length - 1, stamp = this.cadence$stamp;
		for (int i = (int) HashCommon.mix(key) & mask; this.cadence$stamps[i] == stamp; i = (i + 1) & mask)
			if (this.cadence$keys[i] == key) {
				if (CADENCE_VERIFY) cadence$verify(this.cadence$types[i], this.getPathTypeOfMob(this.currentContext, x, y, z, this.mob));
				return this.cadence$types[i];
			}
		PathType type = this.getPathTypeOfMob(this.currentContext, x, y, z, this.mob);
		if (CADENCE_VERIFY && ++cadence$misses % 100000 == 1) System.out.println("cadence-logic: pathCache misses " + cadence$misses + ", hits " + cadence$checked);
		if (type != null) this.cadence$put(key, type);  // (null isn't kept, as computeIfAbsent keeps none)
		return type;
	}

	/** Stores a new entry (probing again: working the type out may have asked the cache itself). */
	@Unique
	private void cadence$put(long key, PathType type) {
		if ((this.cadence$size + 1) * 2 > this.cadence$keys.length) this.cadence$grow();
		int mask = this.cadence$keys.length - 1, stamp = this.cadence$stamp;
		int i = (int) HashCommon.mix(key) & mask;
		while (this.cadence$stamps[i] == stamp) {
			if (this.cadence$keys[i] == key) {
				this.cadence$types[i] = type;
				return;
			}
			i = (i + 1) & mask;
		}
		this.cadence$keys[i] = key;
		this.cadence$types[i] = type;
		this.cadence$stamps[i] = stamp;
		this.cadence$size++;
	}

	@Unique
	private void cadence$grow() {
		long[] keys = this.cadence$keys;
		PathType[] types = this.cadence$types;
		int[] stamps = this.cadence$stamps;
		int stamp = this.cadence$stamp, n = keys.length * 2;
		this.cadence$keys = new long[n];
		this.cadence$types = new PathType[n];
		this.cadence$stamps = new int[n];
		this.cadence$size = 0;
		for (int i = 0; i < keys.length; i++) if (stamps[i] == stamp) this.cadence$put(keys[i], types[i]);
	}

	/** The search is done (vanilla clears its map here): the next search starts on a new stamp. */
	@Inject(method = "done", at = @At("HEAD"))
	private void cadence$newSearch(CallbackInfo ci) {
		this.cadence$size = 0;
		if (this.cadence$keys == null) return;
		if (++this.cadence$stamp == Integer.MAX_VALUE) {  // (once in 2^31 searches: start the stamps over)
			java.util.Arrays.fill(this.cadence$stamps, 0);
			this.cadence$stamp = 1;
		}
		// (old entries' types stay in the array: PathType is an enum, nothing is kept alive by them)
	}
}
