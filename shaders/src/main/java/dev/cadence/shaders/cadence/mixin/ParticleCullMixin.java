package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.EntityCull;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Cadence: particles behind walls are not drawn (EntityCull.particleVisible). A redirect: no boxing per particle. */
@Mixin(QuadParticleGroup.class)
public abstract class ParticleCullMixin {
	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
	private boolean cadence$cullParticle(Frustum frustum, double x, double y, double z) {
		return frustum.pointInFrustum(x, y, z) && EntityCull.particleVisible(x, y, z);
	}
}
