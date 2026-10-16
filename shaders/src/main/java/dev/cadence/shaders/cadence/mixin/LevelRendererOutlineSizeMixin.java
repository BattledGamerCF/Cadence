package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.OutlineTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (OutlineTarget): the outline target sized once a frame, as soon as the frame knows whether it shows outlines. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererOutlineSizeMixin {
	@Shadow private boolean currentFrameRendersEntityOutline;

	@Inject(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;currentFrameRendersEntityOutline:Z",
		opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void cadence$outlineSize(CallbackInfo ci) {
		OutlineTarget.frame((LevelRenderer) (Object) this, this.currentFrameRendersEntityOutline);
	}
}
