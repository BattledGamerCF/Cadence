package dev.cadence.shaders.cadence.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.cadence.shaders.cadence.WorldScale;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (WorldScale): the glowing-outline target, kept at the scaled world's size. */
@Mixin(LevelRenderer.class)
public interface LevelRendererOutlineAccess extends WorldScale.Outline {
	@Override
	@Accessor("entityOutlineTarget")
	RenderTarget cadence$entityOutline();
}
