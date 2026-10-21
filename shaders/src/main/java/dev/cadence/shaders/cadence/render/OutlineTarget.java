package dev.cadence.shaders.cadence.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;

/**
 * Cadence: vanilla's glowing-outline target (a colour and a depth texture at window size, ~43 MB at 2940 x 1840) is only drawn
 * into, imported and blitted in frames that show outlines (LevelRenderer's currentFrameRendersEntityOutline: the Glowing
 * effect, spectral arrows, spectator highlighting); vanilla keeps it allocated all the time. Here it is 1 x 1 until a frame
 * shows outlines, then the main target's size (also World Resolution's scaled world: the size vanilla draws outlines at),
 * and back to 1 x 1 after 10 s without them. Nothing drawn changes. -Dcadence.lazyOutline=false: always the main target's size.
 * Render thread (LevelRendererOutlineSizeMixin, once a frame before the frame graph is built).
 */
public final class OutlineTarget {
	private static final boolean LAZY = !"false".equals(System.getProperty("cadence.lazyOutline"));
	private static final long IDLE_NS = 10_000_000_000L;
	private static long lastShown = Long.MIN_VALUE;

	private OutlineTarget() {
	}

	public static void frame(LevelRenderer levelRenderer, boolean shows) {
		RenderTarget outline = ((WorldScale.Outline) levelRenderer).cadence$entityOutline();
		if (outline == null) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		long now = System.nanoTime();
		if (shows) lastShown = now;
		if (shows || !LAZY) {
			if (outline.width != main.width || outline.height != main.height) outline.resize(main.width, main.height);
		} else if ((outline.width > 1 || outline.height > 1) && (lastShown == Long.MIN_VALUE || now - lastShown > IDLE_NS)) {
			outline.resize(1, 1);
		}
	}
}
