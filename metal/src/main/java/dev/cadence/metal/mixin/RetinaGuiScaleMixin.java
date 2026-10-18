package dev.cadence.metal.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.cadence.metal.Retina;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Cadence (Retina off): the window has half the pixels in each direction, so a chosen GUI Scale is halved (rounded up) to keep
 * menus, text and the HUD the same size on screen as with Retina on. Auto (0) picks its own fit, as always.
 */
@Mixin(Window.class)
abstract class RetinaGuiScaleMixin {
	@ModifyVariable(method = "calculateScale", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int cadence$halveForPointSize(int guiScale) {
		return guiScale > 0 && !Retina.on() ? Math.max(1, (guiScale + 1) / 2) : guiScale;
	}
}
