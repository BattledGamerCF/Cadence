package dev.cadence.shaders.cadence;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Cadence: every Cadence tooltip in one compact shape, so the choices can be seen at a glance: a short first line, then one
 * line per choice with its name in green ("Apple|sharpest, by Apple" -> <green>Apple</green>: sharpest, by Apple), and grey
 * notes ("#..."). A line with neither is plain text.
 */
public final class Tips {
	private Tips() {
	}

	public static Tooltip of(String intro, String... lines) {
		return Tooltip.create(text(intro, lines));
	}

	public static Component text(String intro, String... lines) {
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
