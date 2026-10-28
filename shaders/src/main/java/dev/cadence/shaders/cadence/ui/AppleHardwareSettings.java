package dev.cadence.shaders.cadence.ui;

import dev.cadence.metal.Hdr;
import dev.cadence.metal.rt.RayTracing;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The features built on the Mac's display, ray tracing and Neural Engine hardware (Video Settings > Cadence: Visuals, Voice
 * Control under Extras). Each one's own class holds what it does; this is their buttons and the saved values (the Cadence config).
 */
public final class AppleHardwareSettings {
	private AppleHardwareSettings() {
	}

	/** At startup (ShaderLoaderMod). */
	public static void load() {
		Hdr.setWanted("true".equalsIgnoreCase(ShaderToggle.configValue("hdr", "false").strip()));
		RayTracing.setMode(parse(RayTracing.Mode.class, ShaderToggle.configValue("rayTracing", "OFF"), RayTracing.Mode.OFF));
		RayTracing.setTracer(parse(RayTracing.Tracer.class, ShaderToggle.configValue("rayTracer", "AUTO"), RayTracing.Tracer.AUTO));
		RayTracing.setTorchShadows(!"false".equalsIgnoreCase(ShaderToggle.configValue("torchShadows", "true").strip()));
		dev.cadence.shaders.cadence.voice.VoiceControl.setWanted("true".equalsIgnoreCase(ShaderToggle.configValue("voiceControl", "false").strip()));
		dev.cadence.metal.neural.SmoothTextures.setWanted("true".equalsIgnoreCase(ShaderToggle.configValue("smoothTextures", "false").strip()));
		dev.cadence.metal.neural.StyleFilter.setStyle(parse(dev.cadence.metal.neural.StyleFilter.Style.class, ShaderToggle.configValue("styleFilter", "OFF"),
			dev.cadence.metal.neural.StyleFilter.Style.OFF));
	}

	/** Voice Control: Off / On (dev.cadence.shaders.cadence.voice.VoiceControl). */
	public static Button voiceButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(voiceLabel(), b -> {
			boolean on = !dev.cadence.shaders.cadence.voice.VoiceControl.wanted();
			dev.cadence.shaders.cadence.voice.VoiceControl.setWanted(on);
			ShaderToggle.saveKey("voiceControl", Boolean.toString(on));
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of(Tips.Impact.LOW, "Spoken commands, recognised on your Mac only (Apple's on-device speech recognition; nothing is sent anywhere).",
			"#Say \"open inventory\", \"jump\", \"screenshot\", \"take a photo\", \"save replay\", \"pause\", \"third person\", \"slot three\", "
				+ "\"Cadence say hello\" (a chat message) or \"Cadence command time set day\" (a command). Only whole phrases count: talk that "
				+ "contains a command word does nothing. The first time, macOS asks to let Cadence Helper use the microphone and speech "
				+ "recognition. What it hears is deleted when it stops.")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(voiceLabel()));
		return self[0];
	}

	private static Component voiceLabel() {
		String st = dev.cadence.shaders.cadence.voice.VoiceControl.status();
		return Component.literal("Voice Control: " + (dev.cadence.shaders.cadence.voice.VoiceControl.wanted() ? "On" : "Off") + (st.isEmpty() ? "" : " (" + st + ")"));
	}

	/** Smooth Textures: Off / On (dev.cadence.metal.neural.SmoothTextures). */
	public static Button smoothButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(smoothLabel(), b -> {
			boolean on = !dev.cadence.metal.neural.SmoothTextures.wanted();
			dev.cadence.metal.neural.SmoothTextures.setWanted(on);
			ShaderToggle.saveKey("smoothTextures", Boolean.toString(on));
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of(Tips.Impact.LOW, "Block textures at twice their resolution, upscaled by an ML model on your Mac's Neural Engine.",
			"#The pixel art keeps its look with smooth, sharp detail instead of square pixels up close. Done once in a few seconds "
				+ "(again after a resource pack change); animated textures (water, lava, fire) stay as they are. Uses about 85 MB more "
				+ "graphics memory. Needs Cadence's terrain renderer (not with a shader pack).")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(smoothLabel()));
		return self[0];
	}

	private static Component smoothLabel() {
		String st = dev.cadence.metal.neural.SmoothTextures.status();
		return Component.literal("Smooth Textures: " + (dev.cadence.metal.neural.SmoothTextures.wanted() ? "On" : "Off") + (st.isEmpty() ? "" : " (" + st + ")"));
	}

	/** Style Filter: Off / a painting's style (dev.cadence.metal.neural.StyleFilter, on the Neural Engine). */
	public static Button styleButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(styleLabel(), b -> {
			var all = dev.cadence.metal.neural.StyleFilter.Style.values();
			var next = all[(dev.cadence.metal.neural.StyleFilter.style().ordinal() + 1) % all.length];
			dev.cadence.metal.neural.StyleFilter.setStyle(next);
			ShaderToggle.saveKey("styleFilter", next.name());
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of(Tips.Impact.MEDIUM, "The world repainted in the style of one of Minecraft's paintings, by a neural network on your Mac's Neural Engine.",
			"#The first time a style is chosen, your Mac learns it (about 4 minutes, in the background while you play; it takes a few "
				+ "pictures of your world first). Nothing is downloaded. The network then runs beside the graphics chip, about 35 pictures "
				+ "a second; your HUD stays sharp. Not with a shader pack or World Resolution below 100%.")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(styleLabel()));
		return self[0];
	}

	private static Component styleLabel() {
		String st = dev.cadence.metal.neural.StyleFilter.status();
		return Component.literal("Style Filter: " + dev.cadence.metal.neural.StyleFilter.style().label + (st.isEmpty() ? "" : " (" + st + ")"));
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
		try {
			return Enum.valueOf(type, value.strip().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	/**
	 * Ray Tracing (dev.cadence.metal.rt.RayTracing): Off, then each mode without and with Torch Shadows. The tracer is chosen by
	 * the chip (hardware on M3 and newer, voxel before: RayTracing.Tracer.AUTO); a saved rayTracer= still overrides it.
	 */
	public static Button rayTracingButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(rtLabel(), b -> {
			RayTracing.Mode mode = RayTracing.mode();
			boolean torches = RayTracing.torchShadows();
			if (mode != RayTracing.Mode.OFF && !torches) {
				torches = true;
			} else {
				RayTracing.Mode[] all = RayTracing.Mode.values();
				mode = all[(mode.ordinal() + 1) % all.length];
				torches = false;
			}
			RayTracing.setMode(mode);
			RayTracing.setTorchShadows(torches);
			ShaderToggle.saveKey("rayTracing", mode.name());
			ShaderToggle.saveKey("torchShadows", Boolean.toString(torches));
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of(Tips.Impact.HIGH, "Shadows from the sun and the moon, ray traced through the blocks around you (64 blocks each way).",
			"Shadows|every pixel in the open is tested against the sun: houses, trees and cliffs cast real shadows, and walls facing away "
				+ "from the sun are in shade; torch-lit places stay lit",
			"Lighting (Experimental)|the shadows plus the sky's and the sun's light bounced between blocks, ray traced and kept in a lighting "
				+ "cache: shaded overhangs and caves' mouths, sunlit walls lighting the ground beside them",
			"Neural Lighting (Experimental)|the same light, its cache a small neural network that learns the world's lighting as you play "
				+ "(trained on the graphics chip every frame)",
			"+ Torches|torches, lanterns, lava and glowstone cast ray-traced shadows too: a wall or a pillar between a torch and the "
				+ "floor shadows it",
			"Off|as vanilla",
			"#On an M2 at 1080p, about 1 ms a frame for Shadows, 2.5 ms for Lighting and 3.5 ms for Neural Lighting; Torch Shadows "
					+ "adds about 1 ms where torches light the view. Not with a shader pack (it has its own shadows) "
					+ "or World Resolution below 100%. Players and mobs don't cast shadows yet.")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(rtLabel()));
		return self[0];
	}

	private static Component rtLabel() {
		RayTracing.Mode mode = RayTracing.mode();
		if (mode == RayTracing.Mode.OFF) return Component.literal("Ray Tracing: Off");
		String name = switch (mode) {
			case SHADOWS -> "Shadows";
			case LIGHTING -> "Lighting";
			default -> "Neural";
		};
		// ("RT" with Torches: "Ray Tracing: Lighting + Torches" is wider than the button)
		return Component.literal(RayTracing.torchShadows() ? "RT: " + name + " + Torches" : "Ray Tracing: " + name);
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
		return Tips.of(Tips.Impact.LOW, "Light sources brighter than white, on your screen's extended range: glowstone, lava, torch flames, lanterns and the sun.",
			"#Everything else, the menus and the HUD look as before (on a screen with little room above white, white itself is a touch "
				+ "dimmer, to leave the highlights some). " + screen
				+ " Needs Cadence's terrain renderer for the glowing blocks; with a shader pack, the pack's own picture is shown unchanged.");
	}
}
