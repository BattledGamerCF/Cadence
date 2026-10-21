package dev.cadence.metal.mixin.gen;

import dev.cadence.metal.gen.SurfaceBuffers;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Cadence (SurfaceBuffers): the ore veins' per-chunk density buffer from the thread's reused ones during a surface pass. */
@Mixin(MaterialRuleContext.class)
abstract class GenSurfaceBufferMixin {
	@Redirect(method = "getDensitiesInChunk", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/levelgen/densityfunction/DensityBuffer;createUnpooled(I)Lnet/minecraft/world/level/levelgen/densityfunction/DensityBuffer;"))
	private DensityBuffer cadence$reused(int size) {
		return SurfaceBuffers.take(size);
	}
}
