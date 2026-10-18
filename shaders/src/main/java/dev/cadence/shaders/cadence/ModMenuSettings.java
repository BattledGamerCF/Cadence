package dev.cadence.shaders.cadence;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;

/**
 * Cadence: the settings button on Cadence's entry in Mod Menu's list (loaded by Mod Menu only, when it's installed). Cadence's
 * settings live in Video Settings, so that's what it opens; Cadence HUD and Cadence Shaders are listed under Cadence.
 */
public final class ModMenuSettings implements ModMenuApi {
	private static final ConfigScreenFactory<VideoSettingsScreen> VIDEO_SETTINGS = parent -> {
		Minecraft mc = Minecraft.getInstance();
		return new VideoSettingsScreen(parent, mc, mc.options);
	};

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return VIDEO_SETTINGS;
	}

	@Override
	public Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
		return Map.of("cadence", VIDEO_SETTINGS);
	}
}
