package dev.cadence.shaders.cadence.mixin.ui;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (ShaderKeys): the shader keys, checked with the game's own keys. */
@Mixin(Minecraft.class)
abstract class MinecraftShaderKeysMixin {
	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void cadence$shaderKeys(CallbackInfo ci) {
		dev.cadence.shaders.cadence.ui.ShaderKeys.tick((Minecraft) (Object) this);
	}
}
