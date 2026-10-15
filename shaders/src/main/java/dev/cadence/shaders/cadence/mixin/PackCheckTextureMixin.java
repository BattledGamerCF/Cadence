package dev.cadence.shaders.cadence.mixin;

import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (PackCheck): animated textures (water, lava, fire...) hold still while a pack check compares pictures. */
@Mixin(TextureManager.class)
public abstract class PackCheckTextureMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void cadence$holdAnimations(CallbackInfo ci) {
		if (dev.cadence.shaders.cadence.packcheck.PackCheck.running()) ci.cancel();
	}
}
