// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.own.AnimOnePass;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * One-pass animation: each animation state remembers the sprite contents that made it, so AnimOnePass matches an atlas's states to its
 * animated sprites by identity, not by position and count.
 */
@Mixin(SpriteContents.class)
abstract class OwnAnimIdentityMixin {
	@Inject(method = "createAnimationState", at = @At("RETURN"))
	private void cadence$animOwner(CallbackInfoReturnable<SpriteContents.AnimationState> cir) {
		if (AnimOnePass.ON && cir.getReturnValue() != null) AnimOnePass.owned(cir.getReturnValue(), (SpriteContents) (Object) this);
	}
}
