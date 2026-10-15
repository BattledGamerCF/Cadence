// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.own.OwnVisible;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dcadence.own.int.visible (OwnVisible): a new generation whenever vanilla clears its visible list; its section set dropped with the ViewArea. */
@Mixin(LevelRenderer.class)
abstract class OwnIntVisibleClearMixin {
	@Inject(method = "clearVisibleSections", at = @At("HEAD"))
	private void cadence$visibleCleared(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (OwnVisible.ON || OwnVisible.VERIFY) OwnVisible.cleared();
	}

	/** Every RenderSection is dropped with the ViewArea (joining or reloading: invalidateCompiledGeometry; leaving: resetLevelRenderData). */
	@Inject(method = {"invalidateCompiledGeometry", "resetLevelRenderData"}, at = @At("RETURN"))
	private void cadence$visibleLevelReset(CallbackInfo ci) {
		if (OwnVisible.ON || OwnVisible.VERIFY) OwnVisible.levelReset();
	}
}
