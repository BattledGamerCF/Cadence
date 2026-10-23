package dev.cadence.api;

/**
 * An addon's entry point, declared in its fabric.mod.json: {@code "entrypoints": {"cadence": ["com.example.MyAddon"]}}. Called
 * once at client start on the render thread, with the other client mods' initializers: before the game's window and GPU device
 * exist, so register hooks here ({@link Cadence#onRender}) and make GPU objects from them. A mod without one is never loaded
 * by Cadence.
 */
@FunctionalInterface
public interface CadenceAddon {
	void onCadence();
}
