// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** -Dcadence.metal.atlasDouble: the texture and view everything samples (swapped between the atlas's two copies). */
@Mixin(AbstractTexture.class)
public interface AtlasDoubleTextureAccess {
	@Accessor("texture")
	void cadence$adSetTexture(GpuTexture texture);

	@Accessor("textureView")
	void cadence$adSetTextureView(GpuTextureView view);

	@Accessor("textureView")
	GpuTextureView cadence$adTextureView();
}
