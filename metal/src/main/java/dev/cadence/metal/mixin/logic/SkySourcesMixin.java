package dev.cadence.metal.mixin.logic;

import dev.cadence.metal.logic.SkySources;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A chunk's sky-light sources found section by section (SkySources). */
@Mixin(ChunkSkyLightSources.class)
abstract class SkySourcesMixin {
	@Inject(method = "fillFrom", at = @At("HEAD"), cancellable = true)
	private void cadence$fill(ChunkAccess chunk, CallbackInfo ci) {
		if (SkySources.fill((SkySourcesAccess) this, chunk)) ci.cancel();
	}
}
