package dev.cadence.shaders.cadence.mixin.render;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (FrameRecovery): the level's frame-graph target handles. */
@Mixin(LevelRenderer.class)
public interface LevelRendererTargetsAccess {
	@Accessor("targets")
	LevelTargetBundle cadence$targets();
}
