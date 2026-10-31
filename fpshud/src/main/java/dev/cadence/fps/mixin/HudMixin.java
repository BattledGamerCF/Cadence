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
	@Unique private int cadenceFps$onScreenShown = -1;
	@Unique private String cadenceFps$batteryShown = "";
	/** Cadence's FrameGen.shownFps(int), looked up once (this mod doesn't depend on Cadence's own: null without it). */
	@Unique private static java.lang.invoke.MethodHandle cadenceFps$frameGen;
	@Unique private static boolean cadenceFps$looked;

	@Unique private static java.lang.invoke.MethodHandle cadenceFps$batteryTime;

	/** Cadence's MacSystem.batteryTimeLeft(), "" without it or on AC power. */
	@Unique
	private static String cadenceFps$battery() {
		cadenceFps$onScreen(0);  // (the lookups)
		if (cadenceFps$batteryTime == null) return "";
		try {
			return (String) cadenceFps$batteryTime.invokeExact();
		} catch (Throwable t) {
			return "";
		}
	}

	@Unique
	private static int cadenceFps$onScreen(int rendered) {
		if (!cadenceFps$looked) {
			cadenceFps$looked = true;
			try {
				cadenceFps$frameGen = java.lang.invoke.MethodHandles.publicLookup().findStatic(Class.forName("dev.cadence.metal.framegen.FrameGen"), "shownFps",
					java.lang.invoke.MethodType.methodType(int.class, int.class));
				cadenceFps$batteryTime = java.lang.invoke.MethodHandles.publicLookup().findStatic(Class.forName("dev.cadence.metal.MacSystem"), "batteryTimeLeft",
					java.lang.invoke.MethodType.methodType(String.class));
			} catch (ReflectiveOperationException | LinkageError e) {
				cadenceFps$frameGen = null;
				System.out.println("[cadence-hud] no frame generation counter: " + e);
			}
		}
		if (cadenceFps$frameGen == null) return rendered;
		try {
			return (int) cadenceFps$frameGen.invokeExact(rendered);
		} catch (Throwable t) {
			return rendered;
		}
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void cadenceFps$draw(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if ("false".equals(System.getProperty("cadence.fpsCounter")) || this.isHidden() || this.minecraft.getDebugOverlay().showDebugScreen() || this.minecraft.gui.screen() instanceof LevelLoadingScreen) {
			return;
		}
		int fps = this.minecraft.getFps(), onScreen = cadenceFps$onScreen(fps);
		// (on battery: how long it lasts at this rate, as Cadence works it out; its own change rebuilds the label too)
		String battery = cadenceFps$battery();
		if (fps != this.cadenceFps$shown || onScreen != this.cadenceFps$onScreenShown || !battery.equals(this.cadenceFps$batteryShown)) {
			this.cadenceFps$shown = fps;
			this.cadenceFps$onScreenShown = onScreen;
			this.cadenceFps$batteryShown = battery;
			// (Frame Generation: the frames on screen, and of them the frames rendered)
			this.cadenceFps$label = onScreen > fps ? onScreen + " fps (" + fps + " rendered)" : fps + " fps";
			if (!battery.isEmpty()) this.cadenceFps$label += " · " + battery;
		}
		graphics.text(this.minecraft.font, this.cadenceFps$label, 2, 2, 0xFFFFFFFF, true);
	}
}
