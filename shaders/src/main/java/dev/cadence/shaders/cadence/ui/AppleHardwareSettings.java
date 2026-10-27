package dev.cadence.shaders.cadence.ui;

import dev.cadence.metal.Hdr;
import dev.cadence.metal.rt.RayTracing;
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
		RayTracing.setMode(parse(RayTracing.Mode.class, ShaderToggle.configValue("rayTracing", "OFF"), RayTracing.Mode.OFF));
		RayTracing.setTracer(parse(RayTracing.Tracer.class, ShaderToggle.configValue("rayTracer", "AUTO"), RayTracing.Tracer.AUTO));
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
		try {
			return Enum.valueOf(type, value.strip().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	/** Ray Tracing: Off / Shadows (dev.cadence.metal.rt.RayTracing). */
	public static Button rayTracingButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(rtLabel(), b -> {
			RayTracing.Mode[] all = RayTracing.Mode.values();
			RayTracing.Mode next = all[(RayTracing.mode().ordinal() + 1) % all.length];
			RayTracing.setMode(next);
			ShaderToggle.saveKey("rayTracing", next.name());
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Shadows from the sun and the moon, ray traced through the blocks around you (64 blocks each way).",
			"Shadows|every pixel in the open is tested against the sun: houses, trees and cliffs cast real shadows, and walls facing away "
				+ "from the sun are in shade; torch-lit places stay lit",
			"Off|as vanilla",
			"#Not with a shader pack (it has its own shadows) or World Resolution below 100%. Players and mobs don't cast shadows yet.")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(rtLabel()));
		return self[0];
	}

	private static Component rtLabel() {
		return Component.literal("Ray Tracing: " + RayTracing.mode().label);
	}

	/** Ray Tracer: Auto / Hardware / Voxel. */
	public static Button tracerButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(tracerLabel(), b -> {
			RayTracing.Tracer[] all = RayTracing.Tracer.values();
			RayTracing.Tracer next = all[(RayTracing.tracer().ordinal() + 1) % all.length];
			RayTracing.setTracer(next);
			ShaderToggle.saveKey("rayTracer", next.name());
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("How rays are traced (the picture is the same).",
			"Hardware|Metal ray tracing through an acceleration structure of the world: on M3 and newer the ray tracing hardware does it",
			"Voxel|a walk through the blocks in a compute shader: fastest on M1 and M2, which have no ray tracing hardware",
			"Auto|Hardware on M3 and newer, Voxel before")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(tracerLabel()));
		return self[0];
	}

	private static Component tracerLabel() {
		String now = RayTracing.mode() == RayTracing.Mode.OFF ? "" : RayTracing.usedHardware() ? " (hardware)" : " (voxel)";
		return Component.literal("Ray Tracer: " + RayTracing.tracer().label + now);
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
