package dev.cadence.shaders.cadence.mixin.server;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (ServerChunkStore): the hashed seed a server sends, which tells two worlds behind one address apart. */
@Mixin(BiomeManager.class)
public interface ServerBiomeSeedAccessor {
	@Accessor("biomeZoomSeed")
	long cadence$seed();
}
