// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** -Dcadence.own.int.animCopy: the atlas's sprites, animation states, size and mip count. */
@Mixin(TextureAtlas.class)
public interface OwnAnimAtlasAccess {
	@Accessor("sprites")
	List<TextureAtlasSprite> cadence$sprites();

	@Accessor("animatedTexturesStates")
	List<SpriteContents.AnimationState> cadence$animationStates();

	@Accessor("maxMipLevel")
	int cadence$maxMipLevel();

	@Accessor("width")
	int cadence$width();

	@Accessor("height")
	int cadence$height();

	/** One-pass animation's fallback: vanilla's own upload of this tick's animation frames. */
	@org.spongepowered.asm.mixin.gen.Invoker("uploadAnimationFrames")
	void cadence$uploadAnimationFrames();
}
