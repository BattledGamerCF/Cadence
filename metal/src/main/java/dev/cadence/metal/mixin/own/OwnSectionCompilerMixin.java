// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.own.OwnTerrain;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stats only (-Dcadence.own.stats): the meshing time of every section compile. */
@Mixin(SectionCompiler.class)
abstract class OwnSectionCompilerMixin {
	private static final ThreadLocal<long[]> MCOPT$START = ThreadLocal.withInitial(() -> new long[1]);

	@Inject(method = "compile", at = @At("HEAD"))
	private void cadence$compileStart(SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting, SectionBufferBuilderPack builders,
		CallbackInfoReturnable<SectionCompiler.Results> cir) {
		if (OwnTerrain.STATS) MCOPT$START.get()[0] = System.nanoTime();
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void cadence$compileEnd(SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting, SectionBufferBuilderPack builders,
		CallbackInfoReturnable<SectionCompiler.Results> cir) {
		if (OwnTerrain.STATS) OwnTerrain.compiled(System.nanoTime() - MCOPT$START.get()[0]);
	}
}
