package dev.cadence.shaders.cadence.render;

import dev.cadence.metal.framegen.FrameGen;
import dev.cadence.shaders.cadence.ui.ShaderToggle;
import java.util.Locale;

/**
 * Video Settings > Frame Generation (FrameGen in the metal module does the work): Smooth, Responsive, Neural or Off, saved as
 * frameGeneration in the config; -Dcadence.frameGeneration overrides it. Smooth by default: it runs only where it adds frames
 * the screen can show (Battery Saver on any screen, Recommended and Max Quality on a 120 Hz one: FramerateCapMixin).
 */
public final class FrameGenSetting {
	private FrameGenSetting() {
	}

	/** The saved mode into FrameGen (the shaders mod's start). */
	public static void load() {
		FrameGen.setMode(parse(System.getProperty("cadence.frameGeneration", ShaderToggle.configValue("frameGeneration", "smooth"))));
	}

	static FrameGen.Mode parse(String s) {
		return switch (s.strip().toLowerCase(Locale.ROOT)) {
			case "off", "false" -> FrameGen.Mode.OFF;
			case "responsive" -> FrameGen.Mode.RESPONSIVE;
			case "neural" -> FrameGen.Mode.NEURAL;
			default -> FrameGen.Mode.SMOOTH;
		};
	}

	public static void set(FrameGen.Mode mode) {
		FrameGen.setMode(mode);
		ShaderToggle.saveKey("frameGeneration", mode.name().toLowerCase(Locale.ROOT));
	}

	/** The button's click: Smooth -> Responsive -> Neural -> Off -> Smooth. */
	public static void cycle() {
		set(switch (FrameGen.mode()) {
			case SMOOTH -> FrameGen.Mode.RESPONSIVE;
			case RESPONSIVE -> FrameGen.Mode.NEURAL;
			case NEURAL -> FrameGen.Mode.OFF;
			case OFF -> FrameGen.Mode.SMOOTH;
		});
	}
}
