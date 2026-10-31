package dev.cadence.shaders.cadence.mixin.mesh;

import dev.cadence.shaders.cadence.facecull.FaceCull;
import dev.cadence.shaders.cadence.facecull.FaceGroupsHolder;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cadence (FaceCull): a compiled section's opaque/cutout quads grouped by facing (worker thread). */
@Mixin(SectionCompiler.class)
public abstract class FaceGroupsCompileMixin {
	@Inject(method = "compile", at = @At("RETURN"))
	private void cadence$group(CallbackInfoReturnable<SectionCompiler.Results> cir) {
		SectionCompiler.Results results = cir.getReturnValue();
		if (results != null) ((FaceGroupsHolder) (Object) results).cadence$setFaceGroups(FaceCull.build(results.renderedLayers));
	}
}
