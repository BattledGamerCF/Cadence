package dev.cadence.metal;

import net.fabricmc.api.ClientModInitializer;

/** Cadence (Preload): the class preload, when another mod's pre-launch step made it wait. */
public final class PreloadLater implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Preload.startDeferred();
	}
}
