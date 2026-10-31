package dev.cadence.metal.mixin.alloc;

import dev.cadence.metal.alloc.EntityStates;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (EntityStateReuseMixin): the world's entities being gathered for drawing (render states kept only then). */
@Mixin(LevelExtractor.class)
abstract class EntityStateGatherMixin {
	@Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void cadence$begin(CallbackInfo ci) {
		EntityStates.gathering = true;
	}

	@Inject(method = "extractVisibleEntities", at = @At("RETURN"))
	private void cadence$end(CallbackInfo ci) {
		EntityStates.gathering = false;
	}
}
