package dev.cadence.shaders.cadence.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Cadence: "Far Terrain" (a page under Video Settings, experimental): the world drawn far past the render distance, as the
 * Distant Horizons mod does, with Cadence's own far terrain (dev.cadence.metal.lod) on Metal. Its settings are saved as
 * farTerrain.* in config/cadence-shaders.properties and take effect at the next launch (Profile.farTerrainSettings): far
 * terrain is wired in at startup or not at all, so with it off the game carries none of its code.
 */
public final class FarTerrainScreen extends OptionsSubScreen {
	public FarTerrainScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.literal("Far Terrain (Experimental)"));
	}

	/** Whether this launch draws far terrain (it was on at startup). */
	public static boolean running() {
		return Boolean.getBoolean("cadence.lod") || System.getProperty("cadence.lod.radius") != null;
	}

	static boolean chosen() {
		return "on".equalsIgnoreCase(ShaderToggle.configValue("farTerrain", "off").strip());
	}

	@Override
	protected void addOptions() {
		boolean on = chosen();
		this.list.addHeader(Component.literal(on == running() ? "Changes take effect at the next launch"
			: "Restart Minecraft to turn Far Terrain " + (on ? "on" : "off")).withStyle(on == running() ? ChatFormatting.GRAY : ChatFormatting.YELLOW));
		this.list.addSmall(cycle("Far Terrain", "farTerrain", "off", new String[] {"off", "on"}, new String[] {"Off", "On"},
				Tips.of("The world drawn far past your render distance, like the Distant Horizons mod (experimental).",
					"#Generated from the world's own terrain and trees, replaced by the real chunks as you explore. Uses some memory "
						+ "and power while it fills in; takes effect at the next launch.")),
			cycle("Distance", "farTerrain.distance", "512", new String[] {"128", "256", "512", "1024"},
				new String[] {"128 Chunks", "256 Chunks", "512 Chunks", "1024 Chunks"},
				Tips.of("How far the far terrain reaches.", "#512 chunks is 8 km. Farther costs a little more memory and fill-in time.")));
		this.list.addSmall(cycle("Quality", "farTerrain.quality", "auto", new String[] {"auto", "low", "medium", "high"},
				new String[] {"Auto", "Low", "Medium", "High"},
				Tips.of("Detail in the distance, and the memory it takes.",
					"Auto|Medium on Macs with up to 16 GB, High above",
					"Low|about 150 MB: for 8-core GPUs and below",
					"Medium|about 270 MB: as sharp as High on a laptop screen",
					"High|about 580 MB: for 5K displays")),
			cycle("CPU Load", "farTerrain.cpu", "balanced", new String[] {"low", "balanced", "high"},
				new String[] {"Low", "Balanced", "High"},
				Tips.of("How many cores fill in new far terrain.",
					"Low|one core: slowest to fill in, coolest",
					"Balanced|the cores the game leaves free",
					"High|all but two cores: fastest to fill in")));
		this.list.addSmall(cycle("Unexplored Terrain", "farTerrain.generate", "true", new String[] {"true", "false"},
				new String[] {"Generate", "Explored Only"},
				Tips.of("Where the far terrain comes from.",
					"Generate|the world's own generator draws land you haven't been to (single player and LAN hosts)",
					"Explored Only|only land you have seen: nothing is generated (also what servers show)")),
			cycle("Trees & Plants", "farTerrain.trees", "true", new String[] {"true", "false"}, new String[] {"On", "Off"},
				Tips.of("Trees, grass and flowers in the far terrain. Off: bare ground, faster to fill in.")));
		this.list.addSmall(cycle("Fog", "farTerrain.fog", "normal", new String[] {"normal", "light", "off"},
				new String[] {"Normal", "Light", "Off"},
				Tips.of("Haze over the far terrain.", "Normal|the game's own fog, stretched to the far terrain's reach",
					"Light|half as dense", "Off|clear to the horizon")),
			null);
		this.list.addHeader(Component.literal("Not drawn while a shader pack is on (yet)").withStyle(ChatFormatting.GRAY));
	}

	private Button cycle(String label, String key, String def, String[] values, String[] labels, Tooltip tip) {
		Button[] self = new Button[1];
		self[0] = Button.builder(Component.literal(label + ": " + labelOf(key, def, values, labels)), b -> {
			String now = ShaderToggle.configValue(key, def).strip().toLowerCase(java.util.Locale.ROOT);
			int i = java.util.Arrays.asList(values).indexOf(now);
			int step = Minecraft.getInstance().hasShiftDown() ? values.length - 1 : 1;
			ShaderToggle.saveKey(key, values[(Math.max(0, i) + step) % values.length]);
			// (the header says whether a restart is due: rebuilt with the new value)
			Minecraft.getInstance().gui.setScreen(new FarTerrainScreen(this.lastScreen));
		}).width(Button.DEFAULT_WIDTH).tooltip(tip).build();
		return self[0];
	}

	private static String labelOf(String key, String def, String[] values, String[] labels) {
		String now = ShaderToggle.configValue(key, def).strip().toLowerCase(java.util.Locale.ROOT);
		int i = java.util.Arrays.asList(values).indexOf(now);
		return labels[i < 0 ? java.util.Arrays.asList(values).indexOf(def) : i];
	}

	/** The Video Settings button that opens this page. */
	public static Button button(Screen parent) {
		String state = chosen() ? (running() ? "On" : "On next launch") : (running() ? "Off next launch" : "Off");
		return Button.builder(Component.literal("Far Terrain: " + state + "..."), b -> Minecraft.getInstance().gui.setScreen(new FarTerrainScreen(parent)))
			.width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Terrain drawn far past your render distance, like the Distant Horizons mod (experimental).",
				"#Its distance, quality, memory and fog are on this page.")).build();
	}
}
