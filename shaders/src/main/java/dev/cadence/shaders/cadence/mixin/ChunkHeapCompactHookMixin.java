package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.memory.ChunkHeapCompactor;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (ChunkHeapCompactor): one compaction step after each upload of staged chunk geometry. */
@Mixin(SectionRenderDispatcher.class)
public abstract class ChunkHeapCompactHookMixin {
	@Inject(method = "uploadTerrainBuffersToGpu", at = @At("RETURN"))
	private void cadence$compact(CallbackInfo ci) {
		ChunkHeapCompactor.step((SectionRenderDispatcher) (Object) this);
	}
}
