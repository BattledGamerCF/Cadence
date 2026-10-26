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

	/** Where a rebuilt page starts scrolled to (the page it replaced). */
	private double startScroll;

	@Override
	protected void init() {
		super.init();
		if (this.startScroll > 0) this.list.setScrollAmount(this.startScroll);
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
		this.list.addHeader(Component.literal(on == running() ? (on ? "Far Distance and Look apply now, the rest at the next launch" : "Changes take effect at the next launch")
			: "Restart Minecraft to turn Far Terrain " + (on ? "on" : "off")).withStyle(on == running() ? ChatFormatting.GRAY : ChatFormatting.YELLOW));
		this.list.addSmall(cycle("Far Terrain", "farTerrain", "off", new String[] {"off", "on"}, new String[] {"Off", "On"},
				Tips.of("The world drawn far past your render distance, like the Distant Horizons mod (experimental).",
					"#Generated from the world's own terrain and trees, replaced by the real chunks as you explore. Uses some memory "
						+ "and power while it fills in; takes effect at the next launch.")),
			distanceSlider());
		this.list.addSmall(cycle("Quality", "farTerrain.quality", "auto", new String[] {"auto", "low", "medium", "high"},
				new String[] {"Auto", "Low", "Medium", "High"},
				Tips.of("Detail in the distance, and the memory it takes.",
					"Auto|Low on GPUs with 8 cores or fewer, Medium on Macs with up to 16 GB, High above",
					"Low|about 150 MB: for 8-core GPUs and below",
					"Medium|about 270 MB: as sharp as High on a laptop screen",
					"High|about 580 MB: for 5K displays")),
			cycle("CPU Load", "farTerrain.cpu", "balanced", new String[] {"low", "balanced", "high"},
				new String[] {"Low", "Balanced", "High"},
				Tips.of("How many cores fill in new far terrain.",
					"Low|one core: slowest to fill in, coolest",
					"Balanced|the cores the game leaves free",
					"High|all but two cores: fastest to fill in")));
		this.list.addSmall(cycle("Land", "farTerrain.generate", "true", new String[] {"true", "false"},
				new String[] {"Generated", "Explored Only"},
				Tips.of("Where the far terrain comes from.",
					"Generated|the world's own generator draws land you haven't been to (single player and LAN hosts)",
					"Explored Only|only land you have seen: nothing is generated (also what servers show)")),
			cycle("Trees & Plants", "farTerrain.trees", "true", new String[] {"true", "false"}, new String[] {"On", "Off"},
				Tips.of("Trees, grass and flowers in the far terrain. Off: bare ground, faster to fill in.")));
		this.list.addSmall(cycle("Fog", "farTerrain.fog", "normal", new String[] {"normal", "light", "off"},
				new String[] {"Normal", "Light", "Off"},
				Tips.of("Haze over the far terrain.", "Normal|the game's own fog, stretched to the far terrain's reach",
					"Light|half as dense", "Off|clear to the horizon")),
			cycle("Progress Line", "farTerrain.progress", "on", new String[] {"on", "off"}, new String[] {"On", "Off"},
				Tips.of("A line under the fps counter while the far terrain fills in or pre-generates (\"Far terrain: 63% filled\").")));
		this.list.addSmall(cycle("Far Clouds", "farTerrain.clouds", "off", new String[] {"off", "on"}, new String[] {"Off", "On"},
				Tips.of("Clouds reach out with the far terrain (to 256 chunks at most) instead of stopping at Cloud Distance.",
					"#Costs some frame time and memory for the bigger cloud layer.")),
			null);
		this.list.addHeader(Component.literal("Look (applies now)").withStyle(ChatFormatting.GRAY));
		this.list.addSmall(cycle("Brightness", "farTerrain.brightness", "1.0", new String[] {"0.6", "0.8", "1.0", "1.2", "1.4"},
				new String[] {"60%", "80%", "100%", "120%", "140%"}, Tips.of("How bright the far terrain is drawn (100%: as generated).")),
			cycle("Saturation", "farTerrain.saturation", "1.0", new String[] {"0.5", "0.75", "1.0", "1.25", "1.5"},
				new String[] {"50%", "75%", "100%", "125%", "150%"}, Tips.of("How vivid the far terrain's colours are (100%: as generated).")));
		this.list.addSmall(cycle("Earth Curvature", "farTerrain.curvature", "0", new String[] {"0", "50", "200", "1000"},
				new String[] {"Off", "Gentle", "Strong", "Tiny Planet"},
				Tips.of("The world curving away like a planet, as Distant Horizons' option: far land sinks below the horizon.",
					"Off|flat, as Minecraft is", "Gentle|a planet 1/50 the Earth's size", "Strong|1/200",
					"Tiny Planet|1/1000: a few kilometres around")),
			null);
		if (running()) {
			this.list.addHeader(Component.literal("Pre-generate the far terrain around you, to disk").withStyle(ChatFormatting.GRAY));
			this.list.addSmall(pregen("Pre-generate 1 km", 64), pregen("Pre-generate 4 km", 256));
		}
		Component note = packNote();
		this.list.addHeader(note != null ? note
			: Component.literal("With a shader pack: drawn by packs that support Distant Horizons").withStyle(ChatFormatting.GRAY));
	}

	/**
	 * The selected shader pack without far terrain, in a line (Video Settings shows it too), or null: no pack, or it draws
	 * the far terrain. The far terrain used to vanish with such a pack with nothing said but a log line (S2-07, S2-09).
	 */
	public static @org.jspecify.annotations.Nullable Component packNote() {
		if (!running()) return null;
		var runtime = dev.cadence.shaders.runtime.ShaderRuntime.get();
		if (runtime == null) return null;
		String pack = ShaderToggle.selectedPack().replaceFirst("(?i)\\.zip$", "");
		return switch (runtime.distantTerrainState()) {
			case "none" -> Component.literal(pack + " doesn't support Distant Horizons: no far terrain with it").withStyle(ChatFormatting.YELLOW);
			case "failed" -> Component.literal(pack + "'s far terrain program failed to load: no far terrain (the log says why)").withStyle(ChatFormatting.YELLOW);
			default -> null;
		};
	}

	/** Far Clouds: the cloud distance (chunks) while far terrain draws, else 0 (the game's Cloud Distance). Asked every frame. */
	public static int farCloudChunks() {
		if (farClouds < 0) farClouds = "on".equalsIgnoreCase(ShaderToggle.configValue("farTerrain.clouds", "off").strip()) ? 1 : 0;
		if (farClouds == 0) return 0;
		float reach = dev.cadence.shaders.cadence.render.DistantTerrain.available() ? dev.cadence.metal.lod.LodPackFrame.reach() : 0;
		return reach <= 0 ? 0 : Math.min(256, (int) (reach / 16));
	}

	/** (the Far Clouds setting, read once and again after the page changes it: -1 unknown) */
	private static int farClouds = -1;

	/** Whether the progress line shows (Progress Line setting). */
	public static boolean progressLine() {
		// (asked every frame: the config file is read once, and again after this page changes something)
		if (progress < 0) progress = "off".equalsIgnoreCase(ShaderToggle.configValue("farTerrain.progress", "on").strip()) ? 0 : 1;
		return progress == 1;
	}

	private static int progress = -1;

	/** The look settings (brightness, saturation, curvature) into the running far terrain: they apply live. */
	public static void applyLook() {
		if (!running()) return;
		dev.cadence.metal.lod.LodConfig.look(number("farTerrain.brightness", 1), number("farTerrain.saturation", 1), number("farTerrain.curvature", 0));
	}

	private static float number(String key, float fallback) {
		try {
			return Float.parseFloat(ShaderToggle.configValue(key, Float.toString(fallback)).strip());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private Button pregen(String label, int chunks) {
		return Button.builder(Component.literal(label), b -> {
			int queued = dev.cadence.metal.lod.LodPackFrame.pregenerate(chunks);
			b.setMessage(Component.literal(queued < 0 ? "Not here (no generator)" : queued == 0 ? "Already generated" : "Queued " + queued + " tiles"));
			b.active = false;
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Generates the far terrain within " + chunks * 16 / 1000 + " km of you now, in the background, and keeps it"
			+ " on disk: travelling there later shows it at once.",
			"#Single player and LAN hosts. About " + (chunks <= 64 ? "40 MB, under a minute" : "250 MB, a few minutes") + "; progress on the progress line.")).build();
	}

	private Button cycle(String label, String key, String def, String[] values, String[] labels, Tooltip tip) {
		Button[] self = new Button[1];
		self[0] = Button.builder(Component.literal(label + ": " + labelOf(key, def, values, labels)), b -> {
			String now = ShaderToggle.configValue(key, def).strip().toLowerCase(java.util.Locale.ROOT);
			int i = java.util.Arrays.asList(values).indexOf(now);
			int step = Minecraft.getInstance().hasShiftDown() ? values.length - 1 : 1;
			ShaderToggle.saveKey(key, values[(Math.max(0, i) + step) % values.length]);
			applyLook();
			farClouds = -1;
			progress = -1;
			// (the label changes in place: rebuilding the page at every click lost its scroll position and what Pre-generate
			// said. The Far Terrain switch changes the header too: that page is rebuilt, scrolled where this one was)
			b.setMessage(Component.literal(label + ": " + labelOf(key, def, values, labels)));
			if (key.equals("farTerrain")) {
				FarTerrainScreen next = new FarTerrainScreen(this.lastScreen);
				next.startScroll = this.list.scrollAmount();
				Minecraft.getInstance().gui.setScreen(next);
			}
		}).width(Button.DEFAULT_WIDTH).tooltip(tip).build();
		return self[0];
	}

	private static String labelOf(String key, String def, String[] values, String[] labels) {
		String now = ShaderToggle.configValue(key, def).strip().toLowerCase(java.util.Locale.ROOT);
		int i = java.util.Arrays.asList(values).indexOf(now);
		return labels[i < 0 ? java.util.Arrays.asList(values).indexOf(def) : i];
	}

	/** Far Distance's stops, in chunks (512: the default). */
	private static final int[] DISTANCES = {64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096};

	/** The saved Far Distance, in chunks. */
	static int distance() {
		try {
			return Math.clamp(Integer.parseInt(ShaderToggle.configValue("farTerrain.distance", "512").strip()), dev.cadence.metal.lod.LodConfig.MIN_RADIUS,
				dev.cadence.metal.lod.LodConfig.MAX_RADIUS);
		} catch (NumberFormatException e) {
			return 512;
		}
	}

	/**
	 * Far Distance: how far the far terrain reaches, like Render Distance for the real chunks. Saved, and applied live while far
	 * terrain runs (Lod follows LodConfig.requestRadius; a reach that needs more or fewer clipmap levels builds the far terrain
	 * again once the slider stands still).
	 */
	public static net.minecraft.client.gui.components.AbstractWidget distanceSlider() {
		boolean on = chosen();
		var slider = new net.minecraft.client.gui.components.AbstractSliderButton(0, 0, Button.DEFAULT_WIDTH, 20, Component.empty(), 0) {
			{
				int now = distance(), best = 0;
				for (int i = 0; i < DISTANCES.length; i++) if (Math.abs(DISTANCES[i] - now) < Math.abs(DISTANCES[best] - now)) best = i;
				this.value = (double) best / (DISTANCES.length - 1);
				this.updateMessage();
			}

			private int chunks() {
				return DISTANCES[(int) Math.round(this.value * (DISTANCES.length - 1))];
			}

			@Override
			protected void updateMessage() {
				// (as vanilla's "Render Distance: 12 Chunks"; the distance in km is in the tooltip: a longer label scrolled)
				this.setMessage(Component.literal("Far Distance: " + this.chunks() + " Chunks"));
			}

			@Override
			protected void applyValue() {
				int c = this.chunks();
				if (c == distance()) return;
				ShaderToggle.saveKey("farTerrain.distance", Integer.toString(c));
				if (running()) dev.cadence.metal.lod.LodConfig.requestRadius(c);
			}
		};
		slider.active = on;
		slider.setTooltip(on
			? Tips.of("How far the far terrain reaches, like Render Distance for the real chunks (512 chunks: 8 km; up to 4,096: 65 km).",
				"#Applies now" + (running() ? "" : " (at the next launch: Far Terrain starts then)") + ". Farther takes a little more memory and "
					+ "time to fill in; a big change builds the far terrain again (a second or two, from the disk cache).")
			: Tips.of("Turn Far Terrain on first (the button beside this one).",
				"#Then this sets how far it reaches, like Render Distance."));
		return slider;
	}

	/** The Video Settings button that opens this page. */
	public static Button button(Screen parent) {
		String state = chosen() ? (running() ? "On" : "On next launch") : (running() ? "Off next launch" : "Off");
		return Button.builder(Component.literal("Far Terrain: " + state + "..."), b -> Minecraft.getInstance().gui.setScreen(new FarTerrainScreen(parent)))
			.width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Terrain drawn far past your render distance, like the Distant Horizons mod (experimental).",
				"#Its distance, quality, memory and fog are on this page.")).build();
	}
}
