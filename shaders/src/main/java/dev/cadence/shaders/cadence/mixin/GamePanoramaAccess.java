package dev.cadence.shaders.cadence.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (PanoramaTexture): where the title-screen panorama's cube map lives. */
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public interface GamePanoramaAccess {
	@Accessor("guiRenderer")
	net.minecraft.client.gui.render.GuiRenderer cadence$guiRenderer();
}
