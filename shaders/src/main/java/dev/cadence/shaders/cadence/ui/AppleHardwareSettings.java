package dev.cadence.shaders.cadence.ui;

import dev.cadence.metal.Hdr;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Video Settings > Cadence: Apple Hardware: the features built on the Mac's display, ray tracing and Neural Engine hardware.
 * Each one's own class holds what it does; this is their buttons and the saved values (the Cadence config).
 */
public final class AppleHardwareSettings {
	private AppleHardwareSettings() {
	}

	/** At startup (ShaderLoaderMod). */
	public static void load() {
		Hdr.setWanted("true".equalsIgnoreCase(ShaderToggle.configValue("hdr", "false").strip()));
	}

	/** HDR: Off / On (dev.cadence.metal.Hdr). */
	public static Button hdrButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(hdrLabel(), b -> {
			boolean on = !Hdr.wanted();
			Hdr.setWanted(on);
			ShaderToggle.saveKey("hdr", Boolean.toString(on));
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(hdrTip()).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(hdrLabel()));
		return self[0];
	}

	private static Component hdrLabel() {
		if (!Hdr.wanted()) return Component.literal("HDR: Off");
		float h = Hdr.active() ? Hdr.headroom() : 0;
		return Component.literal("HDR: On" + (h > 1.05f ? String.format(java.util.Locale.ROOT, " (%.1fx)", h) : ""));
	}

	private static net.minecraft.client.gui.components.Tooltip hdrTip() {
		float p = Hdr.potentialHeadroom();
		String screen = p > 1.05f
			? String.format(java.util.Locale.ROOT, "This screen shows highlights up to %.1fx white (less at full brightness: the room left above the brightness you set).", p)
			: "This screen shows no HDR range now (turn it on to find out: macOS reports it once a game asks). Built-in XDR displays reach 16x; most other Mac screens about 2x below full brightness.";
		return Tips.of("Light sources brighter than white, on your screen's extended range: glowstone, lava, torch flames, lanterns and the sun.",
			"#Everything else, the menus and the HUD look exactly as before. " + screen
				+ " Needs Cadence's terrain renderer for the glowing blocks; with a shader pack, the pack's own picture is shown unchanged.");
	}
}
