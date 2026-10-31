package dev.cadence.metal.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.cadence.metal.system.Retina;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Cadence (Retina off): the window has half the pixels in each direction, so a chosen GUI Scale is halved (rounded up) to keep
 * menus, text and the HUD the same size on screen as with Retina on. Auto (0) picks its own fit, as always. Only on a Retina
 * screen: on a 1x monitor the window has the same pixels either way, and halving would make every menu half size.
 */
@Mixin(Window.class)
abstract class RetinaGuiScaleMixin {
	@ModifyVariable(method = "calculateScale", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int cadence$halveForPointSize(int guiScale) {
		return guiScale > 0 && !Retina.on() && dev.cadence.metal.backend.MacSystem.displayScale() >= 1.5 ? Math.max(1, (guiScale + 1) / 2) : guiScale;
	}
}
