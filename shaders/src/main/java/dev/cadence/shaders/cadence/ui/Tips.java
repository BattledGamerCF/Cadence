package dev.cadence.shaders.cadence.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Cadence: every Cadence tooltip in one compact shape, so the choices can be seen at a glance: a short first line, then one
 * line per choice with its name in green ("Apple|sharpest, by Apple" -> <green>Apple</green>: sharpest, by Apple), and grey
 * notes ("#..."). A line with neither is plain text. Settings end with their performance impact (Impact).
 */
public final class Tips {
	private Tips() {
	}

	/**
	 * What a setting does to the frame rate. Costs are rated as in Sodium (only the word coloured: Low green, Medium yellow,
	 * High gold) and say they lower it; settings that raise it say Improves (aqua); None and Varies are plain.
	 */
	public enum Impact {
		NONE("None", null, ""), LOW("Low", ChatFormatting.GREEN, " (lowers fps)"), MEDIUM("Medium", ChatFormatting.YELLOW, " (lowers fps)"),
		HIGH("High", ChatFormatting.GOLD, " (lowers fps)"), IMPROVES("Improves", ChatFormatting.AQUA, " (raises fps)"), VARIES("Varies", null, "");

		final String label;
		final ChatFormatting colour;
		final String effect;

		Impact(String label, ChatFormatting colour, String effect) {
			this.label = label;
			this.colour = colour;
			this.effect = effect;
		}

		/** The same rating with its own note after it instead of the usual one ("Varies (lower only when hot)"). */
		public Rated because(String note) {
			return new Rated(this, " (" + note + ")");
		}
	}

	/** A rating and the words after it. */
	public record Rated(Impact impact, String effect) {
	}

	public static Tooltip of(String intro, String... lines) {
		return Tooltip.create(text(intro, lines));
	}

	/** A tooltip ending with "Performance impact: High (lowers fps)", only the rating's word coloured. */
	public static Tooltip of(Impact impact, String intro, String... lines) {
		return of(new Rated(impact, impact.effect), intro, lines);
	}

	public static Tooltip of(Rated rated, String intro, String... lines) {
		return Tooltip.create(text(intro, lines).append("\n").append(impactLine(rated)));
	}

	/** "Performance impact: High (lowers fps)": the label and the note plain, the word in its colour. */
	public static MutableComponent impactLine(Rated rated) {
		MutableComponent word = Component.literal(rated.impact().label);
		if (rated.impact().colour != null) word = word.withStyle(rated.impact().colour);
		return Component.literal("Performance impact: ").withStyle(ChatFormatting.WHITE).append(word)
			.append(Component.literal(rated.effect()).withStyle(ChatFormatting.WHITE));
	}

	public static MutableComponent text(String intro, String... lines) {
		MutableComponent out = Component.literal(intro);
		for (String line : lines) {
			out.append("\n");
			if (line.startsWith("#")) out.append(Component.literal(line.substring(1)).withStyle(ChatFormatting.GRAY));
			else {
				int bar = line.indexOf('|');
				if (bar < 0) out.append(Component.literal(line));
				else out.append(Component.literal(line.substring(0, bar)).withStyle(ChatFormatting.GREEN))
					.append(Component.literal(": " + line.substring(bar + 1)));
			}
		}
		return out;
	}
}
