package dev.cadence.api;

/**
 * An addon's entry point, declared in its fabric.mod.json: {@code "entrypoints": {"cadence": ["com.example.MyAddon"]}}. Called
 * once at client start, after Cadence has set itself up, on the render thread; a mod without one is never loaded by Cadence.
 */
@FunctionalInterface
public interface CadenceAddon {
	void onCadence();
}
