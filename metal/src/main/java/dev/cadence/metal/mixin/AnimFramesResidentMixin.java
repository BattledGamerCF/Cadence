// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin;

import dev.cadence.metal.ResidentAnim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dcadence.metal.residentAnim: marks the texture creations of a sprite animation's frames (each frame is its own small texture, drawn into
 * the atlas on the ticks that need it) so the device keeps them resident. Does nothing without the flag.
 */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimatedTexture")
public class AnimFramesResidentMixin {
	@Inject(method = "createAnimationState", at = @At("HEAD"))
	private void cadence$residentHead(CallbackInfoReturnable<?> cir) {
		ResidentAnim.creating = ResidentAnim.ON;
	}

	@Inject(method = "createAnimationState", at = @At("RETURN"))
	private void cadence$residentReturn(CallbackInfoReturnable<?> cir) {
		ResidentAnim.creating = false;
	}
}
