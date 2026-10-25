package dev.cadence.shaders.cadence.mixin.ui;

import dev.cadence.shaders.cadence.render.PerformanceOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (PerformanceOverlay): its lines under the fps counter, hidden with the HUD (F1) and while F3 is open. */
@Mixin(Hud.class)
abstract class PerformanceOverlayMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void cadence$overlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!PerformanceOverlay.on()) return;
		Minecraft mc = Minecraft.getInstance();
		if (((Hud) (Object) this).isHidden() || mc.getDebugOverlay().showDebugScreen()) return;
		int y = 12;
		for (String line : PerformanceOverlay.lines(mc.getFps())) {
			graphics.text(mc.font, line, 2, y, 0xFFE0E0E0, true);
			y += 10;
		}
	}
}
