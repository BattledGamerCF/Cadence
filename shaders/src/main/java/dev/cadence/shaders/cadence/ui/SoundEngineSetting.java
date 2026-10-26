package dev.cadence.shaders.cadence.ui;

import dev.cadence.metal.audio.AppleAudio;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Options > Music & Sounds > Sound Engine: Apple Spatial Audio (the default: dev.cadence.metal.audio.AppleAudio) or OpenAL
 * (Minecraft's own). Saved as soundEngine in the Cadence config; a change restarts the game's sound system at once (the
 * sounds playing stop, as when the output device changes). With a mod that drives OpenAL itself (CompatLayers) it stays on
 * OpenAL and the button says why.
 */
public final class SoundEngineSetting {
	private SoundEngineSetting() {
	}

	/** At startup (ShaderLoaderMod), before the sound system starts. */
	public static void load() {
		AppleAudio.setWanted(!"openal".equalsIgnoreCase(ShaderToggle.configValue("soundEngine", "apple").strip()));
	}

	public static Button button() {
		boolean forced = "openal".equalsIgnoreCase(System.getProperty("cadence.audio", "apple"));
		Button[] self = new Button[1];
		self[0] = Button.builder(label(), b -> {
			boolean apple = !AppleAudio.wanted();
			ShaderToggle.saveKey("soundEngine", apple ? "apple" : "openal");
			AppleAudio.setWanted(apple);
			Minecraft.getInstance().getSoundManager().reload();
			self[0].setMessage(label());
		}).width(Button.DEFAULT_WIDTH).tooltip(forced
			? Tips.of("OpenAL: another mod drives OpenAL itself (sound physics, reverb), so Cadence keeps it.",
				"#" + String.join(", ", compatMods()))
			: Tips.of("Which engine plays the game's sound.",
				"Apple Spatial Audio|Apple's own audio engine: spatial audio on headphones and AirPods, Apple's speaker rendering on the Mac's speakers, follows the output you pick in macOS",
				"OpenAL|Minecraft's own engine (OpenAL Soft)")).build();
		self[0].active = !forced;
		return self[0];
	}

	private static Component label() {
		boolean apple = AppleAudio.wanted();
		return Component.literal("Sound Engine: " + (apple ? "Apple Spatial Audio" : "OpenAL"));
	}

	private static java.util.List<String> compatMods() {
		for (var l : dev.cadence.metal.compat.CompatLayers.layers())
			if (l.area().equals("Apple sound engine")) return java.util.List.copyOf(l.mods());
		return java.util.List.of("set by -Dcadence.audio=openal");
	}
}
