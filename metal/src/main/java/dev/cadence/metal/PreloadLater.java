package dev.cadence.metal;

import net.fabricmc.api.ClientModInitializer;

/** Cadence (Preload): the class preload, when another mod's pre-launch step made it wait. */
public final class PreloadLater implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Preload.startDeferred();
		// Cadence API: the addons that declare a "cadence" entry point (dev.cadence.api.CadenceAddon); none: nothing loaded
		for (var addon : net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypointContainers("cadence", dev.cadence.api.CadenceAddon.class)) {
			try {
				addon.getEntrypoint().onCadence();
			} catch (RuntimeException | LinkageError e) {
				System.out.println("[cadence] api: addon " + addon.getProvider().getMetadata().getId() + " failed to start: " + e);
				e.printStackTrace(System.out);
			}
		}
	}
}
