package dev.cadence.shaders.cadence.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (ShaderKeys): the shader keys join the game's key list before the saved keys are read. */
@Mixin(Options.class)
abstract class OptionsShaderKeysMixin {
	@Shadow @Final @Mutable public KeyMapping[] keyMappings;

	@Inject(method = "load", at = @At("HEAD"))
	private void cadence$shaderKeys(CallbackInfo ci) {
		this.keyMappings = dev.cadence.shaders.cadence.ui.ShaderKeys.add(this.keyMappings);
	}
}
