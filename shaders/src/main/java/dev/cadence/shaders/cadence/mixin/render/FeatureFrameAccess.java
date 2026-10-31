package dev.cadence.shaders.cadence.mixin.render;

import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (FrameRecovery): whether the feature dispatcher's prepared frame is still in use after a failed frame. */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public interface FeatureFrameAccess {
	@Accessor("context")
	FeatureFrameContext cadence$context();

	@Accessor("context")
	void cadence$setContext(FeatureFrameContext context);

	@Accessor("submitNodeStorage")
	void cadence$setSubmitNodeStorage(net.minecraft.client.renderer.SubmitNodeStorage storage);
}
