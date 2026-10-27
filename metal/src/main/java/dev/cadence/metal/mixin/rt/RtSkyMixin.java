package dev.cadence.metal.mixin.rt;

import dev.cadence.metal.rt.RayTracing;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ray Tracing: where the sun and the moon are this frame (the sky's extracted state), and the rain. */
@Mixin(SkyRenderer.class)
abstract class RtSkyMixin {
	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void cadence$rtSky(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, CallbackInfo ci) {
		if (RayTracing.mode() != RayTracing.Mode.OFF) RayTracing.sky(state.sunAngle, state.moonAngle, level.getRainLevel(partialTicks));
	}
}
