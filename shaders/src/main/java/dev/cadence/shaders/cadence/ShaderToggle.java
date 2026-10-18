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
 * Cadence: the "Shaders" row in Video Settings. One button cycles Off -> each pack in shaderpacks/ (ZIPs and folders) and
 * saves the choice to config/cadence-shaders.properties (other keys there are kept); a second opens shaderpacks/.
 *
 * <p>A change applies on the next launch: a selected pack changes every chunk mesh's vertex layout and Cadence's terrain
 * hand-off at startup (CadencePackGate, dev.cadence.metal.Profile), so it can't be swapped in a running game. The button says so.
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

	/** The pack picked in Video Settings (not applied yet), or else the saved one; "" for off (Shader Pack Settings). */
	public static String selectedPack() {
		return pending != null ? pending : saved();
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
				p.store(w, "Cadence shaders (Video Settings, Cadence: Shaders). pack= empty is off. liveSwitching=false: Cadence's faster terrain when"
					+ " shaders are off at launch, but turning them on then needs a restart.");
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

	/** The choice shown on the button; applied when Video Settings closes (applyPending), so clicking through packs is free. */
	private static String pending;

	private static Component label(String pack) {
		Component base = Component.literal("Shaders: " + display(pack));
		if (CadencePackGate.hooksApplied()) {
			if (pack.equals(ShaderSwitcher.target())) return base;
			return base.copy().append(Component.literal("  (on close)").withStyle(ChatFormatting.YELLOW));
		}
		if (pack.isEmpty()) return base;
		return base.copy().append(Component.literal("  (restart)").withStyle(ChatFormatting.YELLOW));
	}

	private static final Tooltip TOOLTIP = Tips.of("Realistic light, shadows and water, at a cost in speed.",
		"#Click to pick a pack or Off; it switches when you leave this screen.",
		"#Add packs: put their .zip files in the Shader Packs folder.");
	private static final Tooltip TOOLTIP_RESTART = Tips.of("Click to pick a pack or Off.",
		"#It turns on after a restart (Shader Switching is set to Restart in your config).");

	/** Video Settings closed: switch to the chosen pack now (live), if it differs from the one in use. */
	public static void applyPending() {
		String chosen = pending;
		boolean profileChanged = pendingProfile != null && !pendingProfile.equals(profileAtOpen);
		pending = null;
		pendingProfile = null;
		if (chosen != null && !CadencePackGate.hooksApplied() && !chosen.isEmpty() && !chosen.equals(CadencePackGate.selectedPack()))
			dev.cadence.shaders.cadence.system.Notices.post("Restart Minecraft to turn on " + display(chosen),
				"This session started without shaders and uses Cadence's faster terrain.");
		if (chosen == null || !CadencePackGate.hooksApplied()) return;
		if (!chosen.equals(ShaderSwitcher.target())) ShaderSwitcher.apply(chosen);
		else if (profileChanged && !chosen.isEmpty()) ShaderSwitcher.reload(); // same pack, new quality: reload it
	}

	/** The pack button. */
	public static Button packButton() {
		pending = CadencePackGate.hooksApplied() ? ShaderSwitcher.target() : saved();
		Button[] self = new Button[1];
		boolean none = choices().size() <= 1;
		self[0] = Button.builder(none ? NONE_LABEL : label(pending), b -> {
			List<String> list = choices();
			if (list.size() <= 1) {
				// no packs installed: nothing to cycle through, so say so and open the folder they go in
				openPacksFolder();
				self[0].setMessage(NONE_LABEL);
				return;
			}
			int i = list.indexOf(pending);
			pending = list.get((i + 1) % list.size());
			save(pending);
			self[0].setMessage(label(pending));
			self[0].setTooltip(CadencePackGate.hooksApplied() ? TOOLTIP : TOOLTIP_RESTART);
		}).width(Button.DEFAULT_WIDTH).tooltip(none ? TOOLTIP_NONE
			: CadencePackGate.hooksApplied() ? TOOLTIP : TOOLTIP_RESTART).build();
		return self[0];
	}

	private static final Component NONE_LABEL = Component.literal("Shaders: None Installed").withStyle(ChatFormatting.GRAY);
	private static final Tooltip TOOLTIP_NONE = Tips.of("No shader packs installed yet.",
		"#Click to open the shaderpacks folder, put a pack's .zip in it (BSL, Complementary...), then pick it here.");

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

	/** The saved shader-switching mode: auto (default), instant or restart (old liveSwitching=false: restart). */
	private static String switchingSaved() {
		Properties p = load();
		String m = p.getProperty("shaderSwitching");
		if (m == null) return "false".equalsIgnoreCase(p.getProperty("liveSwitching", "true").strip()) ? "restart" : "auto";
		m = m.strip().toLowerCase(Locale.ROOT);
		return m.equals("instant") || m.equals("restart") ? m : "auto";
	}

	private static Component liveLabel(String mode) {
		String word = switch (mode) {
			case "instant" -> "Instant";
			case "restart" -> "Restart";
			default -> "Auto";
		};
		Component base = Component.literal("Shader Switching: " + word);
		boolean live = dev.cadence.metal.Profile.liveSwitching(mode, null, !saved().isEmpty());
		if (live == CadencePackGate.liveSwitchingAtLaunch()) return base;
		return base.copy().append(Component.literal("  (restart)").withStyle(ChatFormatting.YELLOW));
	}

	/**
	 * Auto (default): a game started without shaders uses Cadence's own, faster terrain (30-70% more fps measured), and
	 * turning shaders on then needs a restart; started with a pack, packs switch instantly. Instant: always switchable, never
	 * the faster terrain. Restart: the faster terrain whenever shaders are off at launch, and no instant switching.
	 */
	public static Button liveButton() {
		String[] mode = {switchingSaved()};
		Button[] self = new Button[1];
		self[0] = Button.builder(liveLabel(mode[0]), b -> {
			mode[0] = switch (mode[0]) {
				case "auto" -> "instant";
				case "instant" -> "restart";
				default -> "auto";
			};
			saveKey("shaderSwitching", mode[0]);
			self[0].setMessage(liveLabel(mode[0]));
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("When a chosen shader pack turns on.",
			"Auto|right away, and Cadence's faster terrain returns when shaders are off",
			"Restart|only after a restart")).build();
		return self[0];
	}

	/** The quality profile shown on the button; "" = the pack's own default. Applied with the pack when the screen closes. */
	private static String pendingProfile;
	private static String profileAtOpen;

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

	private static Component profileLabel(String profile) {
		return Component.literal("Shader Profile: " + (profile.isEmpty() ? "Default" : profile.charAt(0)
			+ profile.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ')));
	}

	/** Cycles the selected pack's quality profiles (pack default first). Applied, with a live reload, when the screen closes. */
	public static Button qualityButton() {
		profileAtOpen = profileFor(selectedPack());
		if (!profileAtOpen.isEmpty() && !profiles(selectedPack()).contains(profileAtOpen)) profileAtOpen = "";  // (not this pack's)
		pendingProfile = profileAtOpen;
		Button[] self = new Button[1];
		self[0] = Button.builder(profileLabel(pendingProfile), b -> {
			String pack = pending != null ? pending : saved();
			List<String> list = new ArrayList<>();
			list.add("");
			list.addAll(profiles(pack));
			// (the pack may have changed since the screen opened: start from that pack's own profile)
			String now = profileFor(pack);
			int i = list.indexOf(list.contains(pendingProfile) ? pendingProfile : now);
			pendingProfile = list.get((i + 1) % list.size());
			saveProfile(pack, pendingProfile);
			self[0].setMessage(profileLabel(pendingProfile));
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("The pack's own quality presets.",
			"Lower|much faster: simpler shadows and lighting",
			"Higher|nicer, slower",
			"#Applies when you leave this screen.")).build();
		return self[0];
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
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("How sharp the world is drawn. Menus, the hotbar, chat and the names above players always stay sharp.",
			"Auto|sharp whenever your Mac keeps up, lower only when needed (recommended)",
			"100%|always full sharpness",
			"85% to 50%|faster and cooler with shaders, softer (50% with Fast: crisp, like a non-Retina screen)",
			"#With shaders: the biggest fps setting. Without shaders the terrain's shape, not its pixels, sets the speed: 100% is best.")).build();
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
			"Off|the game drawn at your screen's point size, each pixel a crisp 2x2 block (like a non-Retina Mac): "
				+ "about twice the fps (M2 Air, no shaders: 530 -> 1,170)",
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
			"Fast|cheapest, the most fps; crisp pixel edges (at 50% exactly sharp)")).build();
		CadenceSettings.onRefresh(() -> self[0].setMessage(upscalerLabel()));
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

	/** Opens shaderpacks/ in Finder (created if missing). */
	public static Button folderButton() {
		return Button.builder(Component.literal("Shader Packs Folder..."), b -> {
			Path dir = packsDir();
			try {
				Files.createDirectories(dir);
				new ProcessBuilder("open", dir.toAbsolutePath().toString()).start();
			} catch (IOException e) {
				System.out.println("[cadence-shaders] can't open " + dir + ": " + e);
			}
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Opens the shaderpacks folder in Finder.")).build();
	}
}
