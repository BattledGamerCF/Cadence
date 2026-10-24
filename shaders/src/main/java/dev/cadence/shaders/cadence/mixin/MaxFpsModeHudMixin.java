package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.render.MaxFpsMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (MaxFpsMode): the HUD not built at all (hotbar, crosshair, status bars, the fps counter: nearly half of a frame's work
 * at 9000 fps); two lines instead, the peak frame rate of this visit and the session's best.
 */
@Mixin(value = Hud.class, priority = 500)
abstract class MaxFpsModeHudMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void cadence$maxFps(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (dev.cadence.metal.record.Record.active()) {
			ci.cancel();
			return;
		}
		if (!MaxFpsMode.on()) return;
		MaxFpsMode.frame();
		var font = Minecraft.getInstance().font;
		String peak = MaxFpsMode.peakLabel();
		graphics.text(font, peak, 4, 4, 0xFFFFFFFF, false);
		graphics.text(font, MaxFpsMode.bestLabel(), 4 + font.width(peak) + 16, 4, 0xFFA0A0A0, false);
		ci.cancel();
	}
}
