// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.qos;

import dev.cadence.metal.Qos;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dcadence.qos.render: the render thread's class, set as the game loop starts. */
@Mixin(Minecraft.class)
abstract class RenderQosMixin {
	@Inject(method = "run", at = @At("HEAD"))
	private void cadence$qos(CallbackInfo ci) {
		Qos.self("render");
	}
}
