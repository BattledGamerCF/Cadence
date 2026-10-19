// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.fps.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "NN fps" in the top-left corner while playing. Hidden with the HUD (F1), while F3 shows its own counter, and when the player
 * turns it off (Video Settings, FPS Counter: the cadence.fpsCounter property, which Cadence's settings set; on unless "false").
 */
@Mixin(Hud.class)
abstract class HudMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow public abstract boolean isHidden();

	// The count changes once a second; the label is rebuilt only then, not every frame.
	@Unique private int cadenceFps$shown = -1;
	@Unique private String cadenceFps$label = "";

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void cadenceFps$draw(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if ("false".equals(System.getProperty("cadence.fpsCounter")) || this.isHidden() || this.minecraft.getDebugOverlay().showDebugScreen() || this.minecraft.gui.screen() instanceof LevelLoadingScreen) {
			return;
		}
		int fps = this.minecraft.getFps();
		if (fps != this.cadenceFps$shown) {
			this.cadenceFps$shown = fps;
			this.cadenceFps$label = fps + " fps";
		}
		graphics.text(this.minecraft.font, this.cadenceFps$label, 2, 2, 0xFFFFFFFF, true);
	}
}
