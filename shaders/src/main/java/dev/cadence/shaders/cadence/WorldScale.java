package dev.cadence.shaders.cadence;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cadence.shaders.runtime.PackUpscale;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.slf4j.LoggerFactory;

/**
 * Cadence: World Resolution without a shader pack. The world and your hand are drawn into smaller targets (RenderScale's
 * scale of the window), then sharpened back up to full size (MetalFX or Cadence's filter, the Upscaler setting) before the
 * HUD, menus, chat and text are drawn at full resolution on top. At 100% nothing changes (vanilla's own targets).
 *
 * <p>How: for the duration of GameRenderer.renderLevel (the world, then the hand) the game renderer's main target and its
 * hand-depth target are swapped for scaled ones; vanilla sizes everything it draws the world into from the main target,
 * so the level renderer's own targets follow. The glowing-outline target follows the main target's size (OutlineTarget; its
 * blit stretches it).
 * After renderLevel the real targets come back and the scaled image is upscaled into the main one, so the outline blit,
 * vanilla's post effects (spectator views, menu blur), screenshots and the GUI all work on the full-size image as before.
 * With a shader pack the pack does its own scaling (ShaderRuntime) and this sits out. Render thread.
 */
public final class WorldScale {
	/** Implemented on GameRenderer by GameRendererWorldScaleMixin. */
	public interface Targets {
		RenderTarget cadence$main();

		RenderTarget cadence$hudDepth();

		void cadence$setTargets(RenderTarget main, RenderTarget hudDepth);
	}

	/** Implemented on LevelRenderer by LevelRendererOutlineAccess. */
	public interface Outline {
		RenderTarget cadence$entityOutline();
	}

	private static final Vector4f CLEAR = new Vector4f(0, 0, 0, 0);
	private static TextureTarget world, hand;
	private static GpuTextureView worldView;
	private static PackUpscale upscale;
	private static RenderTarget savedMain, savedHand;
	private static boolean active, logged, vanillaFrames;

	private WorldScale() {
	}

	/** GameRenderer.renderLevel HEAD. */
	public static void begin(Object gameRenderer) {
		active = false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || dev.cadence.shaders.runtime.ShaderRuntime.get() != null || !RenderSystem.isOnRenderThread()) {
			vanillaFrames = false;
			restoreOutline(mc);
			return;
		}
		if (!vanillaFrames) {
			vanillaFrames = true;
			RenderScale.restartAuto();
		}
		var chunks = mc.levelRenderer.sectionRenderDispatcher();
		RenderScale.frame(System.nanoTime(), chunks != null && chunks.getCompileQueueSize() > 32);
		float scale = RenderScale.get();
		var targets = (Targets) gameRenderer;
		RenderTarget main = targets.cadence$main();
		if (scale >= 0.995f) {
			restoreOutline(mc);
			return;
		}
		int w = Math.max(1, Math.round(main.width * scale)), h = Math.max(1, Math.round(main.height * scale));
		if (world == null || world.width != w || world.height != h) allocate(main, w, h);
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.clearColorAndDepthTextures(world.getColorTexture(), CLEAR, world.getDepthTexture(), 0.0);
		savedMain = main;
		savedHand = targets.cadence$hudDepth();
		targets.cadence$setTargets(world, hand);
		active = true;
	}

	/** GameRenderer.renderLevel RETURN: real targets back, the scaled world upscaled into the main one. */
	public static void end(Object gameRenderer) {
		if (!active) return;
		active = false;
		var targets = (Targets) gameRenderer;
		targets.cadence$setTargets(savedMain, savedHand);
		GpuTextureView destination = savedMain.getColorTextureView();
		if (!RenderScale.metalFx() || !dev.cadence.metal.MetalFxUpscaler.encode(worldView, destination)) {
			float sharpen = RenderScale.sharpenFor(RenderScale.get());
			boolean fast = RenderScale.fastUpscale();
			if (upscale == null || upscale.sharpen() != sharpen || upscale.fast() != fast) {
				if (upscale != null) upscale.close();
				upscale = new PackUpscale(RenderSystem.getDevice(), savedMain.getColorTexture().getFormat(), sharpen, fast);
			}
			upscale.run(RenderSystem.getDevice(), worldView, destination);
		}
		savedMain = null;
		savedHand = null;
	}

	private static void allocate(RenderTarget main, int w, int h) {
		release();
		world = new TextureTarget("Cadence world", w, h, main.getColorTexture().getFormat(), main.getDepthTexture().getFormat());
		hand = new TextureTarget("Cadence hand depth", w, h, null, main.getDepthTexture().getFormat());
		worldView = RenderSystem.getDevice().createTextureView(world.getColorTexture());
		if (!logged || System.getProperty("cadence.selftest") != null) {
			logged = true;
			LoggerFactory.getLogger("cadence").info("World resolution: world drawn at {}x{}, shown at {}x{}", w, h, main.width, main.height);
		}
	}

	/** Back to vanilla sizes (100%, a shader pack, no world); the outline target follows the main one (OutlineTarget). */
	private static void restoreOutline(Minecraft mc) {
		if (world == null) return;
		release();
	}

	private static void release() {
		if (worldView != null) worldView.close();
		if (world != null) world.destroyBuffers();
		if (hand != null) hand.destroyBuffers();
		worldView = null;
		world = null;
		hand = null;
	}

	/** Cadence (WorldClosed): the scaled targets go with the world. */
	public static void releaseWorld() {
		release();
	}

	/** Whether the world is being drawn scaled right now (diagnostics). */
	public static boolean active() {
		return active;
	}
}
