package dev.cadence.shaders.cadence.mixin.hiz;

import dev.cadence.shaders.cadence.facecull.FaceCull;
import dev.cadence.shaders.cadence.hiz.HiZ;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cadence (HiZ): which prepared view is the camera's (the shadow pass prepares its own inside FaceCull's shadow view). */
@Mixin(LevelRenderer.class)
public abstract class HiZPrepareMixin {
	@Inject(method = "prepareChunkRendersIndirect", at = @At("RETURN"))
	private void cadence$mainView(CallbackInfoReturnable<ChunkSectionsToRender> cir) {
		if (!FaceCull.preparingShadow()) HiZ.mainPrepared(cir.getReturnValue());
		else HiZ.shadowPrepared(cir.getReturnValue());
	}
}
