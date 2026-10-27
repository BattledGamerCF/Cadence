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
	private static boolean hudWasHidden, frozeScale;
	/** With Ray Tracing on: the photo is path traced (dev.cadence.metal.rt.RayTracing.startPhoto), this many samples added up first. */
	private static final int PATH_SAMPLES = Integer.getInteger("cadence.photo.samples", 256);
	private static boolean pathTraced;
	private static long pathStarted;

	private PhotoMode() {
	}

	/** Whether a photo is being taken: RenderScale draws at full size, FrameGen stays off. */
	public static boolean active() {
		return countdown > 0;
	}

	public static void take(Minecraft mc) {
		if (countdown > 0 || mc.level == null || dev.cadence.metal.record.Record.active()) return;  // (Render Max FPS: not its frame)
		hudWasHidden = mc.gui.hud.isHidden();
		if (!hudWasHidden) mc.gui.hud.toggle();
		// (only lifted again if this set it: a pack check's freeze stays, BH4-14)
		frozeScale = !RenderScale.frozen;
		RenderScale.frozen = true;
		countdown = 4;  // (ticks: ~0.2 s for the full-size frame to reach the screen)
		// (Ray Tracing on, no shader pack: path traced, the shot taken once enough samples are added up, at most 30 s)
		pathTraced = dev.cadence.shaders.runtime.ShaderRuntime.get() == null && dev.cadence.metal.rt.RayTracing.startPhoto(PATH_SAMPLES);
		pathStarted = System.nanoTime();
	}

	/** A screen opened or the world closed during the countdown: no shot, everything as it was (WorldClosed, ShaderKeys). */
	public static void cancel(Minecraft mc) {
		if (countdown <= 0) return;
		countdown = 0;
		restore(mc);
	}

	private static void restore(Minecraft mc) {
		if (pathTraced) dev.cadence.metal.rt.RayTracing.endPhoto();
		pathTraced = false;
		if (frozeScale) RenderScale.frozen = false;
		frozeScale = false;
		if (!hudWasHidden && mc.gui.hud.isHidden()) mc.gui.hud.toggle();
	}

	/** Once a tick (ShaderKeys.tick, from handleKeybinds: in a world, no screen open): the countdown, then the shot. */
	public static void frame(Minecraft mc) {
		if (countdown <= 0) return;
		if (countdown > 1 || !pathTraced) {
			if (--countdown > 0) return;
		} else if (dev.cadence.metal.rt.RayTracing.photoSamples() < PATH_SAMPLES && System.nanoTime() - pathStarted < 30_000_000_000L) {
			return;  // (still adding up samples: the shot waits)
		} else {
			countdown = 0;
		}
		int samples = pathTraced ? dev.cadence.metal.rt.RayTracing.photoSamples() : 0;
		// (milliseconds in the name: two shots in one second overwrote the first)
		String name = "cadence-photo-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.SSS")) + ".png";
		Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, message -> mc.execute(() -> {
			if (mc.player != null) mc.player.sendSystemMessage(Component.literal(samples > 0 ? "Photo Mode (path traced, " + samples + " samples): " : "Photo Mode: ")
				.append(message));
		}));
		restore(mc);
	}
}
