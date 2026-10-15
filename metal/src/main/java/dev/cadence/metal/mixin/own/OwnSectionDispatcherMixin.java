// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.own.OwnTerrain;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The dispatcher going away (new level, resource reload) takes our sections with it. */
@Mixin(SectionRenderDispatcher.class)
abstract class OwnSectionDispatcherMixin {
	@Inject(method = "dispose", at = @At("HEAD"))
	private void cadence$ownDispose(CallbackInfo ci) {
		OwnTerrain own = OwnTerrain.get();
		if (own != null) own.disposeAll();
	}
}
