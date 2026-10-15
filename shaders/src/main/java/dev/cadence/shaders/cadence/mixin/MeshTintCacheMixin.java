package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.mesh.MeshTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Cadence (MeshTintCache): the blend square's biome lookups, once per position per section build. */
@Mixin(ClientLevel.class)
public abstract class MeshTintCacheMixin {
	@Redirect(method = "calculateBlockTint", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"))
	private Holder<Biome> cadence$biome(ClientLevel level, BlockPos pos) {
		MeshTintCache cache = MeshTintCache.current();
		if (cache == null) return level.getBiome(pos);
		int i = cache.index(pos.getX(), pos.getY(), pos.getZ());
		if (i < 0) return level.getBiome(pos);
		Holder<Biome> biome = cache.get(i);
		if (biome == null) {
			biome = level.getBiome(pos);
			cache.put(i, biome);
		} else if (dev.cadence.shaders.cadence.mesh.MeshStateCache.VERIFY) {
			dev.cadence.shaders.cadence.mesh.MeshStateCache.verify(biome, level.getBiome(pos));
		}
		return biome;
	}
}
