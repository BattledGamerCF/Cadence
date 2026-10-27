package dev.cadence.shaders.cadence.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cadence.shaders.runtime.PackUpscale;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.slf4j.LoggerFactory;

/**
 * Cadence: World Resolution without a shader pack. The world is drawn into a smaller target (RenderScale's
 * scale of the window), then sharpened back up to full size (MetalFX or Cadence's filter, the Upscaler setting) before the
 * HUD, menus, chat and text are drawn at full resolution on top. At 100% nothing changes (vanilla's own targets).
 *
 * <p>How: while LevelRenderer.render draws the world, the game renderer's main target is swapped for a scaled one (the hand
 * comes after, at full size); vanilla sizes everything it draws the world into from the main target,
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
	/** -Dcadence.worldScale.merge=false: the upscale pass without the full-size depth (its own pass; A/B "scalemerge"). */
	public static volatile boolean MERGE = !"false".equals(System.getProperty("cadence.worldScale.merge"));
	private static TextureTarget world;
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
		// (the hand-depth target stays vanilla's: the hand is drawn after finishWorld, into the full-size targets; a scaled one
		// here was never drawn into)
		targets.cadence$setTargets(world, savedHand);
		active = true;
	}

	/**
	 * LevelRenderer.render, as its features' frame data closes (LevelRendererWorldScaleMixin): real targets back, the scaled
	 * world upscaled into the main one, the world's text drawn on it at full resolution (CrispText). The hand is drawn after
	 * this, into the full-size targets.
	 */
	public static void finishWorld(Object gameRenderer) {
		if (!active) return;
		active = false;
		var targets = (Targets) gameRenderer;
		targets.cadence$setTargets(savedMain, savedHand);
		GpuTextureView destination = savedMain.getColorTextureView();
		boolean done = RenderScale.neural() && dev.cadence.metal.NeuralUpscaler.encode(worldView, destination);
		if (!done && (!RenderScale.metalFx() || !dev.cadence.metal.MetalFxUpscaler.encode(worldView, destination))) {
			float sharpen = RenderScale.sharpenFor(RenderScale.get());
			boolean fast = RenderScale.fastUpscale();
			if (upscale == null || upscale.sharpen() != sharpen || upscale.fast() != fast) {
				if (upscale != null) upscale.close();
				upscale = new PackUpscale(RenderSystem.getDevice(), savedMain.getColorTexture().getFormat(), sharpen, fast);
			}
			// the full-size depth goes into the same pass, cleared: the hand (which clears it again), the world's text (which
			// writes all of it) and the HUD then continue this pass instead of loading the full-size image back from memory
			GpuTextureView depth = MERGE && savedMain.getDepthTexture() != null ? savedMain.getDepthTextureView() : null;
			upscale.run(RenderSystem.getDevice(), worldView, destination, depth);
		}
		// Cadence (CrispText): the world's text, held back from the scaled pass, at full resolution on top
		if (savedMain.getDepthTexture() != null && world.getDepthTexture() != null)
			CrispText.drawDeferred(world.getDepthTextureView(), destination, savedMain.getDepthTextureView());
		else CrispText.drop();
		savedMain = null;
		savedHand = null;
	}

	/** GameRenderer.renderLevel RETURN: finishWorld if the level renderer didn't (it always does unless it failed part way). */
	public static void end(Object gameRenderer) {
		finishWorld(gameRenderer);
		CrispText.drop();
	}

	private static void allocate(RenderTarget main, int w, int h) {
		release();
		world = new TextureTarget("Cadence world", w, h, main.getColorTexture().getFormat(), main.getDepthTexture().getFormat());
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
		worldView = null;
		world = null;
	}

	static {
		// (HDR on or off: the scaled world target is made again at its next use, in the new format)
		dev.cadence.metal.Hdr.onRebuild(WorldScale::release);
	}

	/** Cadence (WorldClosed): the scaled targets go with the world. */
	public static void releaseWorld() {
		CrispText.drop();
		release();
	}

	/** Whether the world is being drawn scaled right now (diagnostics). */
	public static boolean active() {
		return active;
	}
}
