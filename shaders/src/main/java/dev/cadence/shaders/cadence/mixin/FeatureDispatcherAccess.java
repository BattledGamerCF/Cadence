package dev.cadence.shaders.cadence.mixin;

import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (FrameRecovery): the dispatcher's one prepared frame. */
@Mixin(FeatureRenderDispatcher.class)
public interface FeatureDispatcherAccess {
	@Accessor("preparedFrame")
	FeatureRenderDispatcher.PreparedFrame cadence$preparedFrame();
}
