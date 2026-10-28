package dev.cadence.shaders.cadence.ui;

import dev.cadence.metal.grade.BiomeGrade;
import dev.cadence.shaders.cadence.head.HeadTracking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The settings for how the game looks and feels that aren't about speed (Video Settings > Cadence: Visuals, and Comfort): each
 * feature's own button and its saved value (the Cadence config).
 */
public final class LookSettings {
	private LookSettings() {
	}

	/** At startup (ShaderLoaderMod). */
	public static void load() {
		BiomeGrade.setStrength(parse(BiomeGrade.Strength.class, ShaderToggle.configValue("biomeColours", "OFF"), BiomeGrade.Strength.OFF));
		HeadTracking.setMode(parse(HeadTracking.Mode.class, ShaderToggle.configValue("headTracking", "OFF"), HeadTracking.Mode.OFF));
	}

	/** Head Tracking: Off / Lean / Look (dev.cadence.shaders.cadence.head.HeadTracking). */
	public static Button headTrackingButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(headLabel(), b -> {
			var all = HeadTracking.Mode.values();
			var next = all[(HeadTracking.mode().ordinal() + 1) % all.length];
			HeadTracking.setMode(next);
			ShaderToggle.saveKey("headTracking", next.name());
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of(Tips.Impact.NONE, "The view follows your head, from your AirPods' motion sensors.",
			"Lean|tilt your head to lean round a corner, turn it to glance; your aim stays with the mouse",
			"Look|your head turns the view (amplified, so your eyes stay on the screen) and your aim with it",
			"Off|as vanilla",
			"#AirPods Pro, AirPods (3rd generation and later), AirPods Max or Beats with head tracking. Straight ahead is found again by "
				+ "itself. The first time, macOS asks to let Cadence Helper use Motion & Fitness; the motion stays on this Mac. First person only.")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(headLabel()));
		return self[0];
	}

	private static Component headLabel() {
		String st = HeadTracking.status();
		return Component.literal("Head Tracking: " + HeadTracking.mode().label + (st.isEmpty() || HeadTracking.mode() == HeadTracking.Mode.OFF ? "" : " (" + st + ")"));
	}

	/** Biome Colours: Off / Subtle / Normal / Strong (dev.cadence.metal.grade.BiomeGrade). */
	public static Button biomeColoursButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(biomeColoursLabel(), b -> {
			var all = BiomeGrade.Strength.values();
			var next = all[(BiomeGrade.strength().ordinal() + 1) % all.length];
			BiomeGrade.setStrength(next);
			ShaderToggle.saveKey("biomeColours", next.name());
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of(Tips.Impact.LOW, "The picture's colours graded for where you are, as a film is graded scene by scene.",
			"#Warm in the desert, cool and pale in the snow, lush in a jungle, grey-green in a swamp, golden at sunset, blue at night, "
				+ "washed out in the rain, red in the Nether. A tiny neural network trained on your Mac (in a moment, the first time) "
				+ "reads the biome, its colours, the time and the weather, so biomes from mods get a grade too. Changes ease in over "
				+ "a few seconds. Not with a shader pack.")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(biomeColoursLabel()));
		return self[0];
	}

	private static Component biomeColoursLabel() {
		return Component.literal("Biome Colours: " + BiomeGrade.strength().label);
	}

	static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
		try {
			return Enum.valueOf(type, value.strip().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
