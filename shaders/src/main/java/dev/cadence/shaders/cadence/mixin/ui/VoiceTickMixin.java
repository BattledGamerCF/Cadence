package dev.cadence.shaders.cadence.mixin.ui;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (VoiceControl): each client tick, menus open or not, the phrases heard since the last one acted on, and
 * Head Tracking's helper started or stopped as its setting says.
 */
@Mixin(Minecraft.class)
abstract class VoiceTickMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void cadence$voice(CallbackInfo ci) {
		dev.cadence.shaders.cadence.voice.VoiceControl.tick((Minecraft) (Object) this);
	}
}
