package dev.cadence.shaders.cadence.mixin.memory;

import dev.cadence.shaders.cadence.mesh.MeshStateCache;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cadence: a finished compile task lets go of its region. Vanilla keeps each section's last compile task
 * (RenderSection.lastCompileTask) until the section is compiled again, and the task keeps the RenderSectionRegion it was
 * built from: copies of the 27 sections around it (palette storage and block entity maps), ~70 KB per compiled section,
 * ~160 MB after a 15-minute flight at RD 16. The region is only read inside doTask (by SectionCompiler.compile), so it is
 * dropped when doTask returns. -Dcadence.releaseRegions=false: keep vanilla's behaviour.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class CompileTaskReleaseMixin {
	@Shadow @Final @Mutable private RenderSectionRegion region;

	@Inject(method = "doTask", at = @At("RETURN"))
	private void cadence$release(SectionBufferBuilderPack buffers, CallbackInfoReturnable<?> cir) {
		if (MeshStateCache.releaseRegions) region = null;
	}
}
