package dev.cadence.api;

import dev.cadence.metal.ApiHooks;
import dev.cadence.metal.MetalCompute;
import dev.cadence.metal.compat.CompatLayers;

/**
 * Cadence's API for other mods. Safe to call from any mod once the game has started; check {@code
 * FabricLoader.getInstance().isModLoaded("cadence")} first if Cadence is optional for your mod.
 *
 * <h2>Asking Cadence to step aside</h2>
 * A mod that needs an area of the game on its vanilla code path (because it hooks the same code Cadence replaces) declares it
 * in its fabric.mod.json; Cadence reads it at launch, before anything is applied:
 * <pre>{@code
 * "custom": { "cadence": { "stepAside": ["terrain", "entityModels", "mixin:SomeCadenceMixin"] } }
 * }</pre>
 * Areas: {@code terrain} (Cadence's own terrain renderer: terrain goes through the game's section pipeline, still on Metal),
 * {@code entityModels}, {@code cpu} (all of Cadence's render-CPU replacements), {@code logic} (all game-logic replacements),
 * {@code farTerrain}, or {@code mixin:} and one of Cadence's mixin class names (simple or full). Cadence also finds most
 * conflicts by itself (it reads every mod's mixins); this is for what it can't see. {@link #steppedAside} tells what is in effect.
 */
public final class Cadence {
	/** The API's version: 1. Additions keep it; anything that changes or removes a method raises it. */
	public static final int API_VERSION = 1;

	private Cadence() {
	}

	/** Whether the game draws with Cadence's Metal renderer (false: Cadence runs on the game's OpenGL backend this session). */
	public static boolean isMetal() {
		return MetalCompute.available();
	}

	/** Whether a shader pack draws the world now. */
	public static boolean shaderPackActive() {
		return !ApiHooks.shaderPack.isEmpty();
	}

	/** The shader pack drawing now (its file name), or "" for none. */
	public static String shaderPack() {
		return ApiHooks.shaderPack;
	}

	/** How far Cadence's far terrain reaches in this level, in blocks, or 0 when it isn't drawing (off, or a dimension without). */
	public static float farTerrainReach() {
		return dev.cadence.metal.lod.LodPackFrame.reach();
	}

	/** Draws at a point of every frame until {@link #removeRenderHook}. Render thread. */
	public static void onRender(RenderStage stage, RenderHook hook) {
		ApiHooks.add(stage, hook);
	}

	public static void removeRenderHook(RenderStage stage, RenderHook hook) {
		ApiHooks.remove(stage, hook);
	}

	/**
	 * What Cadence leaves on the vanilla code path this session, and for which mods: one line per area ("own terrain renderer:
	 * Some Mod"), from its own conflict scan and from the mods' stepAside requests.
	 */
	public static java.util.List<String> steppedAside() {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (CompatLayers.Layer l : CompatLayers.layers()) if (l.applied()) out.add(l.area() + ": " + String.join(", ", l.mods()));
		return out;
	}
}
