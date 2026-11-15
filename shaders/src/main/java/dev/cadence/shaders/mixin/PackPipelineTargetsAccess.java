package dev.cadence.shaders.mixin;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Cadence (PackPipeline): a pack program's colour targets set after the pipeline is built. Minecraft's builder refuses
 * different blending per target (OpenGL's blend state was global); Metal blends each attachment on its own, and packs ask for it
 * with blend.&lt;program&gt;.&lt;buffer&gt;.
 */
@Mixin(RenderPipeline.class)
public interface PackPipelineTargetsAccess {
	@Mutable
	@Accessor("colorTargetStates")
	void cadence$setColorTargetStates(List<ColorTargetState> states);
}
