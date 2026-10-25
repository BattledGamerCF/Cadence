package dev.cadence.shaders.cadence.mixin.chunk;

import dev.cadence.shaders.cadence.render.ChunkBufferRebuild;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence: the point in a chunk reload where a pending buffer rebuild is safe (ChunkBufferRebuild). Does nothing otherwise. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererRebufferMixin {
	@Shadow private SectionRenderDispatcher sectionRenderDispatcher;

	@Inject(method = "invalidateCompiledGeometry",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;clearCompileQueue()V", shift = At.Shift.AFTER))
	private void cadence$rebuildChunkBuffers(CallbackInfo ci) {
		ChunkBufferRebuild.rebuildIfPending(this.sectionRenderDispatcher);
	}
}
