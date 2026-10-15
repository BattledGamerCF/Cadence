// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.own.AnimOnePass;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dcadence.own.int.animOnePass: anything still noted (an atlas ticked outside the texture manager's tick) drawn before the frame renders. */
@Mixin(GameRenderer.class)
public class OwnAnimFlushFrameMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void cadence$animFlushFrame(CallbackInfo ci) {
		AnimOnePass.flush();
	}
}
