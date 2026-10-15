// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** -Dcadence.metal.atlasDouble: the atlas's sprites, animation states, per-mip views (the animation passes' targets) and size. */
@Mixin(TextureAtlas.class)
public interface AtlasDoubleAtlasAccess {
	@Accessor("sprites")
	List<TextureAtlasSprite> cadence$adSprites();

	@Accessor("animatedTexturesStates")
	List<SpriteContents.AnimationState> cadence$adStates();

	@Accessor("mipViews")
	GpuTextureView[] cadence$adMipViews();

	@Accessor("mipViews")
	void cadence$adSetMipViews(GpuTextureView[] views);

	@Accessor("maxMipLevel")
	int cadence$adMaxMip();

	@Accessor("width")
	int cadence$adWidth();

	@Accessor("height")
	int cadence$adHeight();
}
