// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.chunk;

import dev.cadence.metal.chunk.ChunkOpt;
import dev.cadence.metal.chunk.FastPalette;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C4 (-Dcadence.chunk.parse=true|verify, -Dcadence.chunk.serialize=true|verify): the factory's two section codecs, wrapped by
 * FastPalette: decode clean palettes without DFU (parse), encode without DFU (serialize), otherwise vanilla. Same strategies,
 * same default values, same record.
 */
@Mixin(PalettedContainerFactory.class)
abstract class PalettedContainerFactoryMixin {
	@Inject(method = "create", at = @At("RETURN"), cancellable = true)
	private static void cadence$fastCodecs(RegistryAccess registries, CallbackInfoReturnable<PalettedContainerFactory> cir) {
		PalettedContainerFactory f = cir.getReturnValue();
		Registry<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
		cir.setReturnValue(new PalettedContainerFactory(
			f.blockStatesStrategy(),
			f.defaultBlockState(),
			FastPalette.wrap(f.blockStatesContainerCodec(), ChunkOpt.PARSE_FAST ? tag -> FastPalette.blockStates(tag, f.blockStatesStrategy()) : tag -> null,
				ChunkOpt.SERIALIZE ? c -> FastPalette.encodeBlockStates(c, f.blockStatesStrategy()) : null, "blocks"),
			f.biomeStrategy(),
			f.defaultBiome(),
			FastPalette.wrap(f.biomeContainerCodec(), ChunkOpt.PARSE_FAST ? tag -> FastPalette.biomes(tag, f.biomeStrategy(), biomes) : tag -> null,
				ChunkOpt.SERIALIZE ? c -> FastPalette.encodeBiomes(c, f.biomeStrategy()) : null, "biomes")));
	}
}
