// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.own.AnimCopy;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dcadence.own.int.animCopy: the atlas's animation draws through AnimCopy (a small target and copies) instead of passes over whole mips. */
@Mixin(TextureAtlas.class)
public class OwnAnimCopyMixin {
	@Inject(method = "uploadAnimationFrames", at = @At("HEAD"), cancellable = true)
	private void cadence$animCopy(CallbackInfo ci) {
		if (dev.cadence.metal.own.AnimOnePass.upload((TextureAtlas) (Object) this) || !dev.cadence.metal.own.AnimOnePass.ON && AnimCopy.upload((TextureAtlas) (Object) this)) ci.cancel();
	}

	@Inject(method = "uploadAnimationFrames", at = @At("RETURN"))
	private void cadence$animVerify(CallbackInfo ci) {
		AnimCopy.afterVanilla((TextureAtlas) (Object) this);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void cadence$animHash(CallbackInfo ci) {
		AnimCopy.ticked((TextureAtlas) (Object) this);
	}
}
