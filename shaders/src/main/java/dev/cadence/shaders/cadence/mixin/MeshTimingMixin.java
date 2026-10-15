package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.mesh.MeshStateCache;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cadence: the section a worker is about to mesh (MeshLightCache); in self-tests, worker time per section compile (A/Bs). */
@Mixin(SectionCompiler.class)
public abstract class MeshTimingMixin {
	@Unique private static final ThreadLocal<long[]> cadence$start = ThreadLocal.withInitial(() -> new long[1]);

	@Inject(method = "compile", at = @At("HEAD"))
	private void cadence$begin(net.minecraft.core.SectionPos section, net.minecraft.client.renderer.chunk.RenderSectionRegion region,
		com.mojang.blaze3d.vertex.VertexSorting sorting, net.minecraft.client.renderer.SectionBufferBuilderPack pack,
		CallbackInfoReturnable<SectionCompiler.Results> cir) {
		// (MeshLightCache) the section this thread is about to mesh
		dev.cadence.shaders.cadence.mesh.MeshLightCache.begin(section.minBlockX(), section.minBlockY(), section.minBlockZ());
		dev.cadence.shaders.cadence.mesh.MeshTintCache.begin(section.minBlockX(), section.minBlockY(), section.minBlockZ());
		dev.cadence.shaders.cadence.mesh.SmartLeaves.begin(region);
		if (MeshStateCache.TIMING) {
			cadence$start.get()[0] = System.nanoTime();
			MeshStateCache.meshedSection(section.asLong());
		}
	}

	@org.spongepowered.asm.mixin.injection.ModifyArg(method = "compile", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
		ordinal = 0))
	private net.minecraft.core.BlockPos cadence$block(net.minecraft.core.BlockPos pos) {
		return dev.cadence.shaders.cadence.mesh.SmartLeaves.block(pos);
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void cadence$end(net.minecraft.core.SectionPos section, net.minecraft.client.renderer.chunk.RenderSectionRegion region,
		com.mojang.blaze3d.vertex.VertexSorting sorting, net.minecraft.client.renderer.SectionBufferBuilderPack pack,
		CallbackInfoReturnable<SectionCompiler.Results> cir) {
		dev.cadence.shaders.cadence.mesh.MeshTintCache.end();
		dev.cadence.shaders.cadence.mesh.SmartLeaves.end();
		// (MeshStateCache) the cached states go with the build, even where something keeps the region
		((MeshStateCache.Holder) region).cadence$dropStates();
		if (MeshStateCache.TIMING) MeshStateCache.compiled(System.nanoTime() - cadence$start.get()[0]);
	}
}
