// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import dev.cadence.metal.backend.MetalBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Metal goes first in every backend list; OpenGL and Vulkan stay behind it as fallbacks. -Dcadence.metal=false opts out. */
@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
	/** Cadence: the default choice is Metal (Cadence's renderer), so the Graphics API option names it. */
	@Inject(method = "caption", at = @At("RETURN"), cancellable = true)
	private void cadence$caption(CallbackInfoReturnable<net.minecraft.network.chat.Component> cir) {
		if ((Object) this == PreferredGraphicsApi.DEFAULT && Boolean.parseBoolean(System.getProperty("cadence.metal", "true")))
			cir.setReturnValue(dev.cadence.metal.system.Lang.literal("Cadence (Metal)"));
	}

	@Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
	private void cadence$metalFirst(CallbackInfoReturnable<GpuBackend[]> cir) {
		if (!Boolean.parseBoolean(System.getProperty("cadence.metal", "true"))) return;
		GpuBackend[] fallbacks = cir.getReturnValue();
		GpuBackend[] backends = new GpuBackend[fallbacks.length + 1];
		backends[0] = new MetalBackend();
		System.arraycopy(fallbacks, 0, backends, 1, fallbacks.length);
		cir.setReturnValue(backends);
	}
}
