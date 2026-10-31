package dev.cadence.metal.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cadence (NativeShaders): which RenderPipeline each backend CreateInfo is made from. The CreateInfo carries only the
 * pipeline's name, and names aren't unique (the glint and special-glint item pipelines share one, with different defines).
 */
@Mixin(targets = "com.mojang.renderpearl.frontend.shaders.PipelineBuilder")
abstract class NativePipelineSourceMixin {
	@Inject(method = "generateBackendCreateInfo", at = @At("RETURN"))
	private void cadence$madeFrom(RenderPipeline pipeline, ShaderSource source, ReferenceArrayList<BackendRenderPipeline.CreateInfo.Shader> shaders,
		Object2IntOpenHashMap<String> locations, CallbackInfoReturnable<BackendRenderPipeline.CreateInfo> cir) {
		dev.cadence.metal.NativeShaders.madeFrom(cir.getReturnValue(), pipeline);
	}
}
