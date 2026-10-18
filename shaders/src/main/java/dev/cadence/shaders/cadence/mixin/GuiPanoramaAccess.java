package dev.cadence.shaders.cadence.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (PanoramaTexture): where the title-screen panorama's cube map lives. */
@Mixin(net.minecraft.client.gui.render.GuiRenderer.class)
public interface GuiPanoramaAccess {
	@Accessor("cubeMap")
	net.minecraft.client.renderer.CubeMap cadence$cubeMap();
}
