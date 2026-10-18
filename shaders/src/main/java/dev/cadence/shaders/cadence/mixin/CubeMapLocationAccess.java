package dev.cadence.shaders.cadence.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (PanoramaTexture): where the title-screen panorama's cube map lives. */
@Mixin(net.minecraft.client.renderer.CubeMap.class)
public interface CubeMapLocationAccess {
	@Accessor("location")
	net.minecraft.resources.Identifier cadence$location();
}
