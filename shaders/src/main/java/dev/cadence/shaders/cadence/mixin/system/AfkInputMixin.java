package dev.cadence.shaders.cadence.mixin.system;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (AfkMemory): the player's input, as Minecraft's own idle detection sees it. */
@Mixin(FramerateLimitTracker.class)
abstract class AfkInputMixin {
	@Inject(method = "onInputReceived", at = @At("HEAD"))
	private void cadence$input(CallbackInfo ci) {
		dev.cadence.shaders.cadence.memory.AfkMemory.input();
	}
}
