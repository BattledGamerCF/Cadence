package dev.cadence.shaders.cadence.mixin.light;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cadence.shaders.cadence.render.DynamicLights;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Cadence: a particle's light with this frame's dynamic lights (DynamicLights). Particles that glow by themselves keep theirs. */
@Mixin(Particle.class)
public abstract class DynamicLightsParticleMixin {
	@Shadow protected double x;
	@Shadow protected double y;
	@Shadow protected double z;

	@ModifyReturnValue(method = "getLightCoords", at = @At("RETURN"))
	private int cadence$dynamicLight(int packed) {
		return DynamicLights.active() ? DynamicLights.raise(packed, this.x, this.y, this.z) : packed;
	}
}
