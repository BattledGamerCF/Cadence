// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin;

import dev.cadence.metal.backend.AtlasDouble;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dcadence.metal.atlasDouble: the blocks atlas double-buffered (see AtlasDouble). Does nothing without the flag. */
@Mixin(TextureAtlas.class)
public class AtlasDoubleMixin {
	@Inject(method = "upload", at = @At("RETURN"))
	private void cadence$adUploaded(CallbackInfo ci) {
		if (AtlasDouble.ON) AtlasDouble.uploaded((TextureAtlas) (Object) this);
	}

	@Inject(method = "releaseTextures", at = @At("HEAD"))
	private void cadence$adRelease(CallbackInfo ci) {
		if (AtlasDouble.ON) AtlasDouble.release((TextureAtlas) (Object) this);
	}

	@Inject(method = "uploadAnimationFrames", at = @At("HEAD"))
	private void cadence$adBefore(CallbackInfo ci) {
		if (AtlasDouble.ON) AtlasDouble.before((TextureAtlas) (Object) this);
	}

	@Inject(method = "uploadAnimationFrames", at = @At("RETURN"))
	private void cadence$adAfter(CallbackInfo ci) {
		if (AtlasDouble.ON) AtlasDouble.after((TextureAtlas) (Object) this);
	}
}
