package dev.cadence.shaders.cadence;

import org.slf4j.LoggerFactory;

/**
 * Cadence: the player left a world (Minecraft.clearClientLevel). Everything Cadence keeps between frames that belongs to
 * that world is let go (and vanilla's own render data reset happens now, not at the next world), so the title screen doesn't keep the old world in memory (ShadowProxies held the whole client
 * world: its chunks, light data and meshes, hundreds of MB, until the next world replaced it). Render thread.
 */
public final class WorldClosed {
	private WorldClosed() {
	}

	public static void run() {
		try {
			dev.cadence.shaders.cadence.shadow.ShadowProxies.release();
			dev.cadence.shaders.cadence.hiz.HiZ.release();
			dev.cadence.shaders.runtime.ShadowRenderer.releaseWorld();
			WorldScale.releaseWorld();
			ShadowCompileCull.reset();
			dev.cadence.scene.SceneViews.worldChanged();
			var runtime = dev.cadence.shaders.runtime.ShaderRuntime.get();
			if (runtime != null && runtime.uniforms() != null) runtime.uniforms().forgetLevel();
			// vanilla only flags its render data for reset and does it when the next world draws: until then the title
			// screen kept the old world's chunk meshes, GPU geometry and chunks (hundreds of MB). Done now instead (vanilla's
			// own reset; it runs again, harmlessly, on the next world).
			var renderer = net.minecraft.client.Minecraft.getInstance().levelRenderer;
			if (renderer != null) renderer.resetLevelRenderData();
			NativeTrim.purge();
		} catch (RuntimeException e) {
			LoggerFactory.getLogger("cadence").warn("Releasing the closed world's state failed", e);
		}
	}
}
