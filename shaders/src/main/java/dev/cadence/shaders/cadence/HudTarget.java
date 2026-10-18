package dev.cadence.shaders.cadence;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Cadence: vanilla's 3D-HUD depth target (GameRenderer.hud3DTarget, a depth texture at window size: 19 MB at 2940 x 1686) is only
 * drawn into while post effects are on (GameRenderer.renderLevel hands render3dHud !appliedPostEffects.isEmpty(): spectating a
 * creeper, spider or enderman); otherwise the hand and held item draw into the main target's depth. Vanilla keeps it allocated
 * all the time. Here it is 1 x 1 until a frame draws into it, then the main target's size, and back to 1 x 1 after 10 s without
 * (as OutlineTarget does for the glowing-outline target). Nothing drawn changes. -Dcadence.lazyHudDepth=false: as vanilla.
 * Render thread (GameRendererHudTargetMixin, as render3dHud begins).
 */
public final class HudTarget {
	private static final boolean LAZY = !"false".equals(System.getProperty("cadence.lazyHudDepth"));
	private static final long IDLE_NS = 10_000_000_000L;
	private static long lastUsed = Long.MIN_VALUE;

	private HudTarget() {
	}

	public static void frame(Object gameRenderer, boolean used) {
		var targets = (WorldScale.Targets) gameRenderer;
		RenderTarget hud = targets.cadence$hudDepth(), main = targets.cadence$main();
		if (hud == null || main == null) return;
		long now = System.nanoTime();
		if (used) lastUsed = now;
		if (used || !LAZY) {
			if (hud.width != main.width || hud.height != main.height) hud.resize(main.width, main.height);
		} else if ((hud.width > 1 || hud.height > 1) && (lastUsed == Long.MIN_VALUE || now - lastUsed > IDLE_NS)) {
			hud.resize(1, 1);
		}
	}
}
