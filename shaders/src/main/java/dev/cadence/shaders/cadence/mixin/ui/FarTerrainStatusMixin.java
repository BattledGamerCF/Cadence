package dev.cadence.shaders.cadence.mixin.ui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (Video Settings > Far Terrain): while the far terrain fills in or pre-generates, one line under the fps counter
 * ("Far terrain: 63% filled"), as Distant Horizons shows its progress. Gone once it's done; Far Terrain > Progress Line: Off
 * hides it. Nothing at all without far terrain.
 */
@Mixin(Hud.class)
abstract class FarTerrainStatusMixin {
	@Shadow public abstract boolean isHidden();

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void cadence$farTerrainStatus(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!dev.cadence.shaders.cadence.render.DistantTerrain.available() || this.isHidden()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.getDebugOverlay().showDebugScreen() || !dev.cadence.shaders.cadence.ui.FarTerrainScreen.progressLine()) return;
		String line = dev.cadence.metal.lod.LodPackFrame.status();
		if (line == null) return;
		int y = "false".equals(System.getProperty("cadence.fpsCounter")) ? 2 : 12;
		// (below the performance overlay's lines when it's on: both started at 12, drawn over each other, BH13-S05)
		if (dev.cadence.shaders.cadence.render.PerformanceOverlay.on())
			y = 12 + 10 * dev.cadence.shaders.cadence.render.PerformanceOverlay.lines(mc.getFps()).size();
		graphics.text(mc.font, line, 2, y, 0xFFE0E0E0, true);
	}
}
