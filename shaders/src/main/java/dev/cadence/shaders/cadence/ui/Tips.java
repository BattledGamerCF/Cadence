package dev.cadence.shaders.cadence.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Cadence: every Cadence tooltip in one compact shape, so the choices can be seen at a glance: a short first line, then one
 * line per choice with its name in green ("Apple|sharpest, by Apple" -> <green>Apple</green>: sharpest, by Apple), and grey
 * notes ("#..."). A line with neither is plain text. Settings that cost frames end with their performance impact, coloured
 * as in Sodium (Low green, Medium yellow, High gold).
 */
public final class Tips {
	private Tips() {
	}

	/** How much a setting costs in frame rate, at its most demanding choice. */
	public enum Impact {
		LOW("Low", ChatFormatting.GREEN), MEDIUM("Medium", ChatFormatting.YELLOW), HIGH("High", ChatFormatting.GOLD), VARIES("Varies", ChatFormatting.WHITE);

		final String label;
		final ChatFormatting colour;

		Impact(String label, ChatFormatting colour) {
			this.label = label;
			this.colour = colour;
		}
	}

	public static Tooltip of(String intro, String... lines) {
		return Tooltip.create(text(intro, lines));
	}

	/** A tooltip ending with "Performance impact: High" (the impact in its colour). */
	public static Tooltip of(Impact impact, String intro, String... lines) {
		return Tooltip.create(text(intro, lines).append("\n").append(Component.literal("Performance impact: ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal(impact.label).withStyle(impact.colour)));
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
