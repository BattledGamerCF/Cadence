// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.own.OwnLife;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dcadence.own.int.lifeCheck (OwnLife): the periodic scan for sections holding a mesh compiled for another position. */
@Mixin(LevelRenderer.class)
abstract class OwnIntLifeScanMixin {
	@Inject(method = "endFrame", at = @At("HEAD"))
	private void cadence$lifeScan(CallbackInfo ci) {
		if (!OwnLife.CHECK) return;
		ViewArea va = ((LevelRenderer) (Object) this).viewArea();
		if (va != null) OwnLife.scan(((OwnIntViewAreaAccess) va).cadence$sections());
	}
}
