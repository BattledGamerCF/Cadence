package dev.cadence.shaders.cadence.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.CubeMapTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.LoggerFactory;

/**
 * Cadence: the title screen's panorama is a cube map of six 1024 x 1024 images (24 MB of GPU memory), drawn behind menus without
 * a world and the world's loading screen. Vanilla keeps it for the whole game; here it is let go once a world is open and it
 * hasn't been drawn for 10 s, and loaded again (from the current resource packs, as a resource reload would) when the world
 * closes. Loading uploads a texture, which can't happen inside a render pass: it is done as a frame begins (GameRenderer.render)
 * or as the world closes; should something draw the panorama while it's let go, that frame skips it and the next one has it.
 * -Dcadence.releasePanorama=false: kept, as vanilla. Render thread (PanoramaMixin, WorldClosedMixin).
 */
public final class PanoramaTexture {
	private static final boolean ON = !"false".equals(System.getProperty("cadence.releasePanorama"));
	private static final long IDLE_NS = 10_000_000_000L;
	private static Identifier released;
	private static boolean reload;
	private static long lastDrawn = System.nanoTime();

	private PanoramaTexture() {
	}

	/** CubeMap.render: false when the panorama is let go (this frame skips it; the next frame loads it). */
	public static boolean drawing() {
		lastDrawn = System.nanoTime();
		if (released == null) return true;
		reload = true;
		return false;
	}

	/** GameRenderer.render HEAD (no render pass open): load it if asked, or let it go once a world has been open without it for a while. */
	public static void frame() {
		if (!ON) return;
		Minecraft mc = Minecraft.getInstance();
		if (reload || released != null && mc.level == null) {
			load(mc);
			return;
		}
		if (released == null && mc.level != null && System.nanoTime() - lastDrawn > IDLE_NS) {
			try {
				Identifier where = location(mc);
				if (where == null) return;
				mc.getTextureManager().release(where);
				released = where;
			} catch (RuntimeException e) {
				LoggerFactory.getLogger("cadence").warn("Panorama texture: couldn't release it", e);
			}
		}
	}

	/** Minecraft.updateLevelInEngines with no level (the world closed): loaded now, before the title screen draws. */
	public static void worldClosed() {
		if (ON && released != null) load(Minecraft.getInstance());
	}

	private static void load(Minecraft mc) {
		reload = false;
		Identifier where = released;
		if (where == null) return;
		released = null;
		lastDrawn = System.nanoTime();
		try {
			mc.getTextureManager().registerAndLoad(where, new CubeMapTexture(where));
		} catch (RuntimeException e) {
			LoggerFactory.getLogger("cadence").warn("Panorama texture: couldn't load it", e);
		}
	}

	private static Identifier location(Minecraft mc) {
		var gui = ((dev.cadence.shaders.cadence.mixin.render.GamePanoramaAccess) mc.gameRenderer).cadence$guiRenderer();
		var cube = gui == null ? null : ((dev.cadence.shaders.cadence.mixin.render.GuiPanoramaAccess) gui).cadence$cubeMap();
		return cube == null ? null : ((dev.cadence.shaders.cadence.mixin.render.CubeMapLocationAccess) cube).cadence$location();
	}
}
