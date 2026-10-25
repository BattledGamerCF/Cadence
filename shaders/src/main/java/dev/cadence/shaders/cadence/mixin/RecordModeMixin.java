package dev.cadence.shaders.cadence.mixin;

import dev.cadence.metal.record.Record;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (Record Mode, dev.cadence.metal.record.Record): the game's frame replaced by the record frame (compute only). */
// (priority 400: before every other hook on GameRenderer.render, so a record frame runs none of them)
@Mixin(value = GameRenderer.class, priority = 400)
abstract class RecordModeMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void cadence$record(CallbackInfo ci) {
		// (under a screen the record frame is made, and the game goes on to draw the screen over it: MaxFpsModeMixin)
		if (Record.active() && Record.frame() && !Record.screenOpen()) ci.cancel();
	}
}
