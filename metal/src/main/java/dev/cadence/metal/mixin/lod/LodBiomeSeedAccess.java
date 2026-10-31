package dev.cadence.metal.mixin.lod;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The hashed seed a server sends at login (kept by the client level's BiomeManager): on a server, the far terrain's cache is
 * kept per world with it, so a reset map (or another server on the same address) doesn't show the old one's land (LodCacheDir).
 */
@Mixin(BiomeManager.class)
public interface LodBiomeSeedAccess {
	@Accessor("biomeZoomSeed")
	long cadence$biomeZoomSeed();
}
