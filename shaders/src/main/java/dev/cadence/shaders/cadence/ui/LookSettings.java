package dev.cadence.shaders.cadence.ui;

import dev.cadence.metal.grade.BiomeGrade;
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
		return dev.cadence.metal.system.Lang.literal("Biome Colours: " + BiomeGrade.strength().label);
	}

	static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
		try {
			return Enum.valueOf(type, value.strip().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
