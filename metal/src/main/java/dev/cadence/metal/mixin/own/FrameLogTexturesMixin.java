// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.FrameLog;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (-Dcadence.own.int.frameLog): the texture manager's tick (texture animations) in the frame it runs in. */
@Mixin(TextureManager.class)
public class FrameLogTexturesMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void cadence$frameLogTexturesHead(CallbackInfo ci) {
		FrameLog.begin(FrameLog.TEXTURES);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void cadence$frameLogTexturesReturn(CallbackInfo ci) {
		FrameLog.end(FrameLog.TEXTURES);
	}
}
