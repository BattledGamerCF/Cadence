package dev.cadence.shaders.cadence.render;

import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

/**
 * Photo Mode (key F9, Controls > Miscellaneous): a screenshot of the world at full quality. For the few frames it takes, the
 * world is drawn at the window's full resolution (Auto resolution's scale and the upscaler set aside, the shader pack at its
 * own full size), Frame Generation and Auto resolution pause and the HUD is hidden; the screenshot is saved where F2 saves
 * them (screenshots/, named cadence-photo-...), and everything comes back as it was. Nothing is saved to the settings.
 */
public final class PhotoMode {
	/** Frames left before the shot (the scale change takes a frame to reach the pack's targets), 0: not taking one. */
	private static int countdown;
	private static boolean hudWasHidden;

	private PhotoMode() {
	}

	/** Whether a photo is being taken: RenderScale draws at full size, FrameGen stays off. */
	public static boolean active() {
		return countdown > 0;
	}

	public static void take(Minecraft mc) {
		if (countdown > 0 || mc.level == null) return;
		hudWasHidden = mc.gui.hud.isHidden();
		if (!hudWasHidden) mc.gui.hud.toggle();
		RenderScale.frozen = true;
		countdown = 4;  // (ticks: ~0.2 s for the full-size frame to reach the screen)
	}

	/** Once a tick (ShaderKeys.tick, from handleKeybinds: in a world, no screen open): the countdown, then the shot. */
	public static void frame(Minecraft mc) {
		if (countdown <= 0) return;
		if (--countdown > 0) return;
		String name = "cadence-photo-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".png";
		Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, message -> mc.execute(() -> {
			if (mc.player != null) mc.player.sendSystemMessage(Component.literal("Photo Mode: ").append(message));
		}));
		RenderScale.frozen = false;
		if (!hudWasHidden && mc.gui.hud.isHidden()) mc.gui.hud.toggle();
	}
}
