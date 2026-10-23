package dev.cadence.api;

/** The points of Cadence's frame where addons can draw ({@link Cadence#onRender}), in the order they come. */
public enum RenderStage {
	/**
	 * The level's opaque phase is drawn (terrain, entities, block entities): opaque world geometry goes here. {@link
	 * RenderContext#pass} is the game's open main pass (colour and depth): draw into it with Minecraft's pipelines.
	 */
	AFTER_OPAQUE,
	/** The level's translucent phase is drawn (water, glass, translucent entities). {@link RenderContext#pass} is the open main pass. */
	AFTER_TRANSLUCENT,
	/** The whole level is drawn (before the hand and the GUI). No pass is open: open your own on {@link RenderContext#mainTarget}. */
	AFTER_LEVEL,
	/** The GUI is drawn (everything but the screen overlays of menus). No pass is open. */
	AFTER_HUD
}
