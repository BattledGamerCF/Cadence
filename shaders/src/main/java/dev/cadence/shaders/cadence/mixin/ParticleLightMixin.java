package dev.cadence.shaders.cadence.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cadence: a particle's light lookup reuses one mutable position instead of a new BlockPos per particle per frame (the
 * largest single source of render-thread garbage in busy scenes without shaders). Same block (BlockPos.containing floors
 * the same way); the lookups don't keep the position. Render thread only, else vanilla's.
 */
@Mixin(Particle.class)
public abstract class ParticleLightMixin {
	@Unique private static final BlockPos.MutableBlockPos cadence$lightPos = new BlockPos.MutableBlockPos();

	@Redirect(method = "getLightCoords", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"))
	private BlockPos cadence$lightPos(double x, double y, double z) {
		return RenderSystem.isOnRenderThread() ? cadence$lightPos.set(x, y, z) : BlockPos.containing(x, y, z);
	}
}
