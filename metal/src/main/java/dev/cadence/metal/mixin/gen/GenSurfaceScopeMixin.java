package dev.cadence.metal.mixin.gen;

import dev.cadence.metal.gen.SurfaceBuffers;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (SurfaceBuffers): the surface pass's begin and end, between which its ore-vein buffers are reused. */
@Mixin(MaterialSystem.class)
abstract class GenSurfaceScopeMixin {
	@Inject(method = "buildSurface", at = @At("HEAD"))
	private void cadence$enter(CallbackInfo ci) {
		SurfaceBuffers.enter();
	}

	@Inject(method = "buildSurface", at = @At("RETURN"))
	private void cadence$exit(CallbackInfo ci) {
		SurfaceBuffers.exit();
	}
}
