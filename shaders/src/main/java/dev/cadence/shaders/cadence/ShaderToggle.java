package dev.cadence.shaders.cadence;

import dev.cadence.shaders.CadencePackGate;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Cadence: the shader settings behind Video Settings: the chosen pack (saved to config/cadence-shaders.properties, other keys
 * there kept; picked in ShaderPacksScreen and switched live by ShaderSwitcher), each pack's quality profile, World
 * Resolution, Sharpening, Retina and Shadow Updates.
 */
public final class ShaderToggle {
	private static final String OFF = "";

	private ShaderToggle() {
	}

	/** The file every save writes: config/cadence-shaders.properties. */
	static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(dev.cadence.metal.Legacy.SHADERS_CONFIG);
	}

	/** The file to read: config/cadence-shaders.properties, or the pre-rename config/minecraft-shader-loader.properties while only that exists. */
	static Path readableConfigFile() {
		return dev.cadence.metal.Legacy.readable(FabricLoader.getInstance().getConfigDir(), dev.cadence.metal.Legacy.SHADERS_CONFIG, dev.cadence.metal.Legacy.SHADERS_CONFIG_OLD);
	}

	private static Path packsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
	}

	private static Properties load() {
		Properties p = new Properties();
		Path cfg = readableConfigFile();
		if (Files.isRegularFile(cfg)) {
			try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
				dev.cadence.metal.ConfigFiles.load(p, r);
			} catch (IOException | IllegalArgumentException e) {
				// treated as no selection
			}
		}
		return p;
	}

	/** The pack chosen in Shader Packs (saved, maybe still turning on); "" for off. */
	public static String selectedPack() {
		return saved();
	}

	public static Path packsDirectory() {
		return packsDir();
	}

	/**
	 * The quality profile chosen for this pack (profile.&lt;pack&gt;=), or the older shared profile= when none was chosen for it yet;
	 * "" is the pack's default. (A shared profile once leaked into other packs: BSL's MINIMUM refused MakeUp at launch.)
	 */
	public static String profileFor(String pack) {
		Properties p = load();
		String own = p.getProperty("profile." + pack);
		return (own != null ? own : p.getProperty("profile", "")).strip();
	}

	public static void saveProfile(String pack, String profile) {
		saveKey("profile." + pack, profile);
	}

	/** A key from the config file, or {@code fallback}. */
	public static String configValue(String key, String fallback) {
		return load().getProperty(key, fallback);
	}

	/** The pack saved in the config (what the next launch will use), "" for off. */
	static String saved() {
		String pack = System.getProperty("cadence.shaders.pack");
		return pack != null ? pack.strip() : load().getProperty("pack", OFF).strip();
	}

	/** Off, then every pack in shaderpacks/ by name; the saved one kept even if its file is gone. */
	static List<String> choices() {
		List<String> out = new ArrayList<>();
		out.add(OFF);
		Path dir = packsDir();
		if (Files.isDirectory(dir)) {
			try (Stream<Path> s = Files.list(dir)) {
				s.filter(p -> Files.isDirectory(p) || p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
					.map(p -> p.getFileName().toString())
					.filter(n -> !n.startsWith("."))
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.forEach(out::add);
			} catch (IOException e) {
				// just Off
			}
		}
		String saved = saved();
		if (!saved.isEmpty() && !out.contains(saved)) out.add(saved);
		return out;
	}

	static void save(String pack) {
		saveKey("pack", pack);
	}

	public static void saveKey(String key, String value) {
		Properties p = load();
		p.setProperty(key, value);
		Path cfg = configFile();
		try {
			Files.createDirectories(cfg.getParent());
			try (Writer w = Files.newBufferedWriter(cfg, StandardCharsets.UTF_8)) {
				p.store(w, "Cadence (Video Settings > Shaders...). pack= empty is off. shaderSwitching=restart: packs turn on only after a"
					+ " restart (the default switches them live).");
			}
		} catch (IOException e) {
			System.out.println("[cadence-shaders] can't save " + cfg + ": " + e);
		}
	}

	public static String display(String pack) {
		if (pack.isEmpty()) return "Off";
		String name = pack;
		if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = name.substring(0, name.length() - 4);
		return name.replace('_', ' ');
	}

	/**
	 * Shader Packs: {@code pack} chosen ("" for off). Saved, and switched to right away when this launch can switch live;
	 * otherwise (Shader Switching set to Restart in the config) it turns on at the next launch.
	 */
	public static void choose(String pack) {
		save(pack);
		if (CadencePackGate.hooksApplied()) ShaderSwitcher.apply(pack);
	}

	/** Video Settings' Shaders button: the pack in use, and the Shader Packs list behind it. */
	public static Button packsButton(net.minecraft.client.gui.screens.Screen parent) {
		String pack = CadencePackGate.hooksApplied() ? ShaderSwitcher.target() : saved();
		boolean none = choices().size() <= 1;
		Component label = Component.literal("Shaders: " + (none && pack.isEmpty() ? "Add Packs" : display(pack)) + "...");
		return Button.builder(label, b -> net.minecraft.client.Minecraft.getInstance().gui.setScreen(new ShaderPacksScreen(parent)))
			.width(Button.DEFAULT_WIDTH)
			.tooltip(none ? Tips.of("No shader packs installed yet.",
					"#Open this, then drop a pack's .zip file on the window (BSL, Complementary...), or put it in the folder.")
				: Tips.of("Realistic light, shadows and water, at a cost in speed.", "#Pick a pack or Off from the list.",
					"#In game: O opens this list, K turns shaders off and back on (Controls > Miscellaneous).")).build();
	}

	/** Opens shaderpacks/ in Finder, creating it if missing. */
	static void openPacksFolder() {
		Path dir = packsDir();
		try {
			Files.createDirectories(dir);
			new ProcessBuilder("open", dir.toAbsolutePath().toString()).start();
		} catch (IOException e) {
			System.out.println("[cadence-shaders] can't open " + dir + ": " + e);
		}
	}

	/** The profiles a pack defines (profile.NAME= in shaders/shaders.properties), in file order. */
	static List<String> profiles(String pack) {
		List<String> out = new ArrayList<>();
		if (pack.isEmpty()) return out;
		Path p = packsDir().resolve(pack);
		try {
			String text;
			if (Files.isDirectory(p)) {
				Path props = p.resolve("shaders").resolve("shaders.properties");
				if (!Files.isRegularFile(props)) return out;
				text = Files.readString(props, StandardCharsets.ISO_8859_1);
			} else {
				try (var zip = new java.util.zip.ZipFile(p.toFile())) {
					var entry = zip.getEntry("shaders/shaders.properties");
					if (entry == null) return out;
					try (var in = zip.getInputStream(entry)) {
						text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
					}
				}
			}
			for (String line : text.split("\\R")) {
				String t = line.strip();
				if (!t.startsWith("profile.")) continue;
				int eq = t.indexOf('=');
				if (eq > 8) {
					String name = t.substring(8, eq).strip();
					if (!name.isEmpty() && !out.contains(name)) out.add(name);
				}
			}
		} catch (IOException | RuntimeException e) {
			// no profiles to offer
		}
		return out;
	}

	private static final float[] SCALES = {1.0f, 0.85f, 0.75f, 0.67f, 0.5f};

	private static Component scaleLabel() {
		if (RenderScale.isAuto()) return Component.literal("Resolution: Auto (" + Math.round(RenderScale.get() * 100) + "%)");
		return Component.literal("Resolution: " + Math.round(RenderScale.get() * 100) + "%");
	}

	/** World Resolution (RenderScale, with or without shaders): Auto, then 100% down to 50%. Live. */
	public static Button scaleButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(scaleLabel(), b -> {
			if (RenderScale.isAuto()) {
				RenderScale.set(SCALES[0]);
			} else {
				// the next step down from the current value (a value between steps, e.g. 67% from an older version or a config
				// edit, went up to the second step before)
				float now = RenderScale.get();
				float next = -1;
				for (float v : SCALES) if (v < now - 0.01f) { next = v; break; }
				if (next < 0) RenderScale.setAuto();
				else RenderScale.set(next);
			}
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("How sharp the world is drawn (menus and text always stay sharp).",
			"Auto|lower only when your Mac can't keep up (recommended)",
			"100%|always full sharpness",
			"85% to 50%|more fps with shaders, softer",
			"#Without shaders, 100% is usually just as fast.")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(scaleLabel()));
		return self[0];
	}

	/** Cadence: Retina as configured for the next launch (retina= in this file; the window is made with it at startup). */
	public static boolean retinaConfigured() {
		return !"false".equalsIgnoreCase(configValue("retina", "true"));
	}

	public static void setRetina(boolean on) {
		saveKey("retina", Boolean.toString(on));
	}

	private static Component retinaLabel() {
		boolean want = retinaConfigured(), now = dev.cadence.metal.Retina.on();
		Component base = Component.literal("Retina: " + (want ? "On" : "Off"));
		return want == now ? base : base.copy().append(Component.literal("  (restart)").withStyle(ChatFormatting.YELLOW));
	}

	/** Retina: the window at the display's full resolution, or at its point size (a quarter of the pixels). Next launch. */
	public static Button retinaButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(retinaLabel(), b -> {
			setRetina(!retinaConfigured());
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("How many pixels the game draws on a Retina display.",
			"On|every pixel of your display, the sharpest picture",
			"Off|a quarter of the pixels, each a crisp 2x2 block: about twice the fps",
			"#Takes effect when you restart Minecraft.")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(retinaLabel()));
		return self[0];
	}

	/** Sharpening: how a world drawn below 100% is brought back up to full size. Live. */
	public static Button upscalerButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(upscalerLabel(), b -> {
			RenderScale.setUpscaler((RenderScale.upscaler() + 1) % 3);
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("When Resolution is below 100%, how the picture is sharpened back:",
			"Apple|sharpest edges, Apple's MetalFX (recommended with shaders)",
			"Smooth|a little softer, very even",
			"Fast|cheapest, the most fps; crisp pixel edges (at 50% exactly sharp)",
			"#Greyed out at 100% without shaders: nothing to sharpen.")).build();
		Runnable state = () -> {
			self[0].setMessage(upscalerLabel());
			// (unused at a fixed 100% without a pack: greyed, saying so; Auto or a pack's own scaling can use it)
			self[0].active = RenderScale.isAuto() || RenderScale.get() < 0.995f || dev.cadence.shaders.runtime.ShaderRuntime.get() != null;
		};
		state.run();
		CadenceSettings.onRefresh(state);
		return self[0];
	}

	/** Shadow Updates (ShadowReuse); saved as shadowRate=. */
	public static Button shadowRateButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(shadowRateLabel(), b -> {
			ShadowReuse.setRate(ShadowReuse.rate() == 1 ? 2 : 1);
			saveKey("shadowRate", Integer.toString(ShadowReuse.rate()));
			CadenceSettings.refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Shaders only: how often sun shadows are redrawn.",
			"Every 2nd Frame|about 15% more fps; moving shadows a little less smooth (recommended)",
			"Every Frame|smoothest, slower")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(shadowRateLabel()));
		if (dev.cadence.shaders.runtime.ShaderRuntime.get() == null) {
			self[0].active = false;
			self[0].setTooltip(Tips.of("Shaders only: how often sun shadows are redrawn.", "#Turn a shader pack on to change it."));
		}
		return self[0];
	}

	private static Component shadowRateLabel() {
		int r = ShadowReuse.effectiveRate();
		Component base = Component.literal("Shadows: " + (r == 1 ? "Every Frame" : "Every " + r + (r == 2 ? "nd" : r == 3 ? "rd" : "th") + " Frame"));
		return r > ShadowReuse.rate() ? base.copy().withStyle(ChatFormatting.YELLOW) : base;
	}

	private static Component upscalerLabel() {
		return Component.literal("Sharpening: " + switch (RenderScale.upscaler()) {
			case RenderScale.UPSCALE_SMOOTH -> "Smooth";
			case RenderScale.UPSCALE_FAST -> "Fast";
			default -> "Apple";
		});
	}
}
