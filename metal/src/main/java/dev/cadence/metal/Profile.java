// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Flag profiles: one place that turns on a set of -Dcadence.* switches without editing the launcher's JVM arguments.
 * <ul>
 * <li>{@code -Dcadence.profile=NAME}, or {@code profile=NAME} in {@code <game dir>/config/cadence.properties}, applies
 * the jar's {@code /cadence/profiles/NAME.properties} (e.g. {@code recommended}: the measured, accepted wins).</li>
 * <li>Any {@code cadence.*} key in config/cadence.properties is applied too, and wins over the profile's value. (Before the
 * rename the file was config/mcopt.properties with {@code mcopt.*} keys: it is read when config/cadence.properties does not
 * exist, its keys under their new names.)</li>
 * <li>A flag given on the command line wins over both: a key is only set when System.getProperty(key) is still null.</li>
 * </ul>
 * With no profile named anywhere, {@code indie} applies (our own renderer); {@code profile=none} applies no profile,
 * exactly the old no-profile behaviour (vanilla's renderer). If config/cadence.properties is absent it is written with the effective profile ({@code indie}, or what -Dcadence.profile names)
 * and comment lines on how to turn it off and how to try far terrain. For {@code alpha} on the small tier (GPU under
 * 10 cores, or 8 GB of RAM or less, or either unreadable) the keys in {@link #SMALL_OUT} are left out. It runs first in every mixin config plugin
 * and in the preLaunch entrypoint, before any of our classes read a flag; the first call does the work, later calls
 * return at once.
 */
public final class Profile {
	private static boolean applied;
	/** Alpha keys left out on the small tier: the 4096-entry clone cache costs memory an 8 GB / small-GPU Mac lacks. */
	static final java.util.List<String> SMALL_OUT = java.util.List.of("cadence.chunk.clones", "cadence.chunk.clonesCleanup");
	static final String DEFAULT = "indie";

	private Profile() {
	}

	public static synchronized void apply() {
		Legacy.migrateProperties(); // -Dmcopt.* / -DminecraftShaders.* from before the rename, under their new names (new names win)
		if (applied) return;
		applied = true;
		JvmDefaults.apply(gameDir()); // Cadence: first of all (may restart Java with the default arguments)
		shaderPackSelected(); // before the profile's flags, so its explicit-wins rule keeps our hand-off
		farTerrainSettings(); // Video Settings > Far Terrain, before the profile's flags (an explicit flag still wins)
		applyFlags();
		distantHorizonsOnOpenGl();
		dev.cadence.metal.compat.CompatLayers.apply(); // after the flags (it may turn cadence.own off), before any is read
	}

	/**
	 * Cadence: a shader pack selected for the shader loader (the shaders module, cadence_shaders), or its live switching
	 * on (liveSwitching=, default true: its hooks then load with no pack too, so packs can be turned on in game), means terrain must
	 * go through the game's standard section pipeline, the one the loader hooks to give the pack its geometry and material
	 * IDs; our own near terrain replaces that pipeline, and the two patch the same six classes. So cadence.own stays off (the
	 * Metal backend still draws everything), and the translated-shader disk cache goes on: a pack builds ~120 pipelines where
	 * vanilla builds a handful. Explicit flags win: a -D on the command line or a key in config/cadence.properties. Without the
	 * loader, or with no pack selected, nothing here runs. A pack chosen while the game is running takes effect next launch.
	 */
	private static void shaderPackSelected() {
		String pack;
		boolean live;
		try {
			if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cadence_shaders")) return;
			Properties p = new Properties();
			Path cfg = Legacy.readable(gameDir().resolve("config"), Legacy.SHADERS_CONFIG, Legacy.SHADERS_CONFIG_OLD);
			if (Files.isRegularFile(cfg)) {
				try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
					dev.cadence.metal.ConfigFiles.load(p, r);
				}
			}
			pack = System.getProperty("cadence.shaders.pack", p.getProperty("pack", ""));
			// Cadence shader switching (shaderSwitching=auto|instant|restart, default auto): with "instant" the loader's hooks
			// load even with no pack, so ours must stay off; "auto" does that only when a pack is on at launch, so a game started
			// without shaders gets Cadence's faster own terrain (turning shaders on then needs a restart).
			live = liveSwitching(p.getProperty("shaderSwitching"), p.getProperty("liveSwitching"), !pack.isBlank());
		} catch (IOException | RuntimeException e) {
			System.out.println("[cadence] profile: can't read the Cadence Shaders config: " + e);
			return;
		}
		if (pack.isBlank() && !live) return;
		Properties ours = new Properties();
		Path cfg = Legacy.readable(gameDir().resolve("config"), Legacy.CONFIG, Legacy.CONFIG_OLD);
		if (Files.isRegularFile(cfg)) {
			try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
				dev.cadence.metal.ConfigFiles.load(ours, r);
			} catch (IOException e) {
				// the profile reports it
			}
		}
		StringBuilder set = new StringBuilder();
		// (cadence.own is no longer turned off here: our terrain hands over to the pack at run time, OwnTerrain.packActive)
		for (String[] kv : new String[][] {{"cadence.startup.mslCache", "true"}}) {
			if (System.getProperty(kv[0]) == null && ours.getProperty(kv[0]) == null && ours.getProperty("mcopt." + kv[0].substring("cadence.".length())) == null) {
				System.setProperty(kv[0], kv[1]);
				set.append(' ').append(kv[0]).append('=').append(kv[1]);
			}
		}
		System.out.println("[cadence] shader " + (pack.isBlank() ? "live switching on" : "pack '" + pack.strip() + "' selected")
			+ ": the loader's hooks on, Cadence's terrain hands over while a pack draws;"
			+ (set.isEmpty() ? " (kept your explicit flags)" : " set" + set));
	}

	/**
	 * Video Settings > Far Terrain (experimental): the far-terrain switches it stands for, read at launch (far terrain is wired
	 * in at startup or not at all: off costs nothing). Saved by the screen as farTerrain.* in config/cadence-shaders.properties.
	 * A -D flag or a key in config/cadence.properties wins over each. On sets the recommended profile's far-terrain set (mesh
	 * renderer, seamless hand-off, screen-priority streaming, GPU publish, NEON noise, yield to the game's own chunks).
	 */
	private static void farTerrainSettings() {
		Properties ui = new Properties(), ours = new Properties();
		try {
			Path cfg = Legacy.readable(gameDir().resolve("config"), Legacy.SHADERS_CONFIG, Legacy.SHADERS_CONFIG_OLD);
			if (Files.isRegularFile(cfg)) try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
				dev.cadence.metal.ConfigFiles.load(ui, r);
			}
			Path own = Legacy.readable(gameDir().resolve("config"), Legacy.CONFIG, Legacy.CONFIG_OLD);
			if (Files.isRegularFile(own)) try (Reader r = Files.newBufferedReader(own, StandardCharsets.UTF_8)) {
				dev.cadence.metal.ConfigFiles.load(ours, r);
			}
		} catch (IOException | RuntimeException e) {
			return;
		}
		if (!"on".equalsIgnoreCase(ui.getProperty("farTerrain", "off").strip())) return;
		java.util.Map<String, String> set = new java.util.LinkedHashMap<>();
		set.put("cadence.lod", "true");
		set.put("cadence.lod.render", "mesh");
		set.put("cadence.lod.fade", "instant");
		set.put("cadence.lod.handoff", "drawn");
		set.put("cadence.lod.prio", "screen");
		set.put("cadence.lod.publish", "gpu");
		set.put("cadence.lod.nativeNoise", "neon");
		set.put("cadence.lod.yield", "missing:0");
		set.put("cadence.lod.handoffPush", "0.9995");
		String distance = ui.getProperty("farTerrain.distance", "").strip();
		if (distance.matches("\\d{2,4}")) set.put("cadence.lod.radius", Integer.toString(Math.max(dev.cadence.metal.lod.LodConfig.MIN_RADIUS, Math.min(dev.cadence.metal.lod.LodConfig.MAX_RADIUS, Integer.parseInt(distance)))));
		switch (ui.getProperty("farTerrain.quality", "auto").strip().toLowerCase(java.util.Locale.ROOT)) {
			case "low" -> set.put("cadence.lod.small", "true");
			case "medium" -> set.put("cadence.lod.n", "1024");
			case "high" -> set.put("cadence.lod.n", "2048");
			// auto: by the Mac's memory (LodConfig.defaultN: Medium up to 16 GB, High above). It was Low on GPUs of 8 cores or
			// fewer (the blockier terrain from ~190 blocks; Medium cost 4.0 ms of GPU on an 8-core M2 Air 200 blocks up); with
			// level 0's near detail drawn only where a block is 4+ pixels (LodConfig.DETAIL_PX) Medium is 2.65 ms there, and full
			// detail reaches 440 blocks instead of 190. The smallest GPUs (6 cores or fewer: the MacBook Neo's 5) keep Low:
			// nothing measured there says Medium fits (BH6-03)
			default -> {
				int gpu = gpuCores();
				if (gpu > 0 && gpu <= 6) set.put("cadence.lod.small", "true");
			}
		}
		int cores = Runtime.getRuntime().availableProcessors();
		switch (ui.getProperty("farTerrain.cpu", "balanced").strip().toLowerCase(java.util.Locale.ROOT)) {
			case "low" -> set.put("cadence.lod.threads", "1");
			case "high" -> set.put("cadence.lod.threads", Integer.toString(Math.max(2, cores - 2)));
			default -> { }  // balanced: the cores the game and the GPU driver leave
		}
		if ("false".equalsIgnoreCase(ui.getProperty("farTerrain.generate", "true").strip())) set.put("cadence.lod.generate", "false");
		if ("on".equalsIgnoreCase(ui.getProperty("farTerrain.meshShaders", "off").strip())) set.put("cadence.lod.meshShader", "true");
		if ("false".equalsIgnoreCase(ui.getProperty("farTerrain.trees", "true").strip())) {
			set.put("cadence.lod.trees", "false");
			set.put("cadence.lod.plants", "false");
			set.put("cadence.lod.treeLevels", "0");
		}
		for (String look : new String[] {"brightness", "saturation", "curvature"}) {
			String v = ui.getProperty("farTerrain." + look, "").strip();
			if (v.matches("[0-9]+(\\.[0-9]+)?")) set.put("cadence.lod." + look, v);
		}
		switch (ui.getProperty("farTerrain.fog", "normal").strip().toLowerCase(java.util.Locale.ROOT)) {
			case "light" -> set.put("cadence.lod.haze", "2.0");
			case "off" -> {
				set.put("cadence.lod.haze", "8.0");
				set.put("cadence.lod.fogStart", "0.97");
			}
			default -> { }
		}
		StringBuilder applied = new StringBuilder();
		for (var e : set.entrySet()) {
			String k = e.getKey();
			if (System.getProperty(k) != null || ours.getProperty(k) != null) continue;
			System.setProperty(k, e.getValue());
			if (!k.equals("cadence.lod.render") && !k.startsWith("cadence.lod.fade") && !k.startsWith("cadence.lod.handoff") && !k.startsWith("cadence.lod.prio")
				&& !k.startsWith("cadence.lod.publish") && !k.startsWith("cadence.lod.nativeNoise") && !k.startsWith("cadence.lod.yield"))
				applied.append(' ').append(k.substring("cadence.".length())).append('=').append(e.getValue());
		}
		System.out.println("[cadence] Far Terrain (experimental) on from Video Settings:" + applied);
	}

	/**
	 * Cadence: whether this launch loads the shader loader's hooks. auto (the default) and instant: always, so packs switch in
	 * game, and our own faster terrain still draws whenever no pack is on (it steps aside while one is: OwnTerrain.packActive;
	 * before that, auto chose one or the other at launch and switching needed a restart); restart: only when a pack is on at
	 * launch. The old key liveSwitching=false means restart. Shared with the shaders module (CadencePackGate), which must agree.
	 */
	public static boolean liveSwitching(String mode, String oldLive, boolean packAtLaunch) {
		String m = mode == null ? ("false".equalsIgnoreCase(oldLive == null ? "" : oldLive.strip()) ? "restart" : "auto")
			: mode.strip().toLowerCase(java.util.Locale.ROOT);
		return switch (m) {
			case "restart" -> packAtLaunch;
			default -> true;  // auto and instant: our terrain steps aside while a pack draws (OwnTerrain.packActive)
		};
	}

	/**
	 * Distant Horizons draws through the Metal backend with its default Blaze3D renderer (DhRenderApiMixin), next to our own
	 * terrain: it draws its LODs in LevelRenderer.prepareTranslucents and its fades in executeOutline / executeOit, none of
	 * which our renderer replaces. Its OpenGL renderer can't: DH picks that one when its config says renderingEngine =
	 * "OPEN_GL" (or when Iris is loaded, which needs Sodium and so never loads with this build), and on the Metal backend it
	 * then stops at startup ("API doesn't match"). Then dev.cadence.metal defaults to false (OpenGL, exactly as -Dcadence.metal=false;
	 * no own renderer). An explicit dev.cadence.metal wins. Without DH nothing here runs.
	 */
	/** Iris itself, not Cadence's optional Iris provider (mod id iris, a version ending +cadence). */
	public static boolean realIris(net.fabricmc.loader.api.FabricLoader loader) {
		return loader.getModContainer("iris").map(c -> !c.getMetadata().getVersion().getFriendlyString().contains("+cadence")).orElse(false);
	}

	private static void distantHorizonsOnOpenGl() {
		if (System.getProperty("cadence.metal") != null) return;
		String why;
		try {
			net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
			if (!loader.isModLoaded("distanthorizons")) return;
			why = realIris(loader) ? "with Iris" : dhOpenGlEngine() ? "set to its OpenGL renderer" : null;
		} catch (Throwable t) {
			return;
		}
		if (why == null) return;
		System.setProperty("cadence.metal", "false");
		System.out.println("[cadence] Distant Horizons " + why + " needs OpenGL, using OpenGL; Cadence's other optimizations stay on (-Dcadence.metal=true overrides)");
	}

	/** config/DistantHorizons.toml says renderingEngine = "OPEN_GL" (its default is AUTO: Blaze3D on 26.x). */
	private static boolean dhOpenGlEngine() {
		Path toml = gameDir().resolve("config").resolve("DistantHorizons.toml");
		if (!Files.isRegularFile(toml)) return false;
		try {
			for (String line : Files.readAllLines(toml, StandardCharsets.UTF_8)) {
				String t = line.strip();
				if (t.startsWith("renderingEngine")) return t.replace(" ", "").startsWith("renderingEngine=\"OPEN_GL\"");
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[cadence] profile: can't read " + toml + ": " + e);
		}
		return false;
	}

	private static void applyFlags() {
		Properties file = new Properties();
		Path cfg = Legacy.readable(gameDir().resolve("config"), Legacy.CONFIG, Legacy.CONFIG_OLD);
		if (Files.isRegularFile(cfg)) {
			try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
				dev.cadence.metal.ConfigFiles.load(file, r);
			} catch (IOException e) {
				System.out.println("[cadence] profile: can't read " + cfg + ": " + e);
			}
		}
		String name = System.getProperty("cadence.profile", file.getProperty("profile", "")).trim();
		if (name.isEmpty()) name = DEFAULT;
		if (!Files.exists(cfg)) writeDefault(cfg, name); // first launch: record the effective profile (none stays none)
		Map<String, String> flags = new TreeMap<>();
		if (!name.isEmpty() && !name.equals("none")) {
			Properties p = new Properties();
			try (InputStream in = Profile.class.getResourceAsStream("/cadence/profiles/" + name + ".properties")) {
				if (in == null) {
					System.out.println("[cadence] profile: no profile named '" + name + "' (ignored)");
				} else {
					dev.cadence.metal.ConfigFiles.load(p, in);
				}
			} catch (IOException e) {
				System.out.println("[cadence] profile " + name + ": " + e);
			}
			p.stringPropertyNames().forEach(k -> flags.put(k, p.getProperty(k).trim()));
			if (name.equals("alpha")) tier(flags);
		}
		// pre-rename keys (mcopt.*) first, so a cadence.* key for the same switch wins
		file.stringPropertyNames().stream().filter(k -> k.startsWith("mcopt.")).sorted().forEach(k -> flags.put(Legacy.modernKey(k), file.getProperty(k).trim()));
		file.stringPropertyNames().stream().filter(k -> k.startsWith("cadence.")).forEach(k -> flags.put(k, file.getProperty(k).trim()));
		if (flags.isEmpty()) return;
		StringBuilder set = new StringBuilder(), kept = new StringBuilder();
		flags.forEach((k, v) -> {
			if (System.getProperty(k) == null) {
				System.setProperty(k, v);
				set.append(' ').append(k).append('=').append(v);
			} else {
				kept.append(' ').append(k).append('=').append(System.getProperty(k));
			}
		});
		System.out.println("[cadence] profile " + (name.isEmpty() ? "(config only)" : name) + ": set" + set
			+ (kept.isEmpty() ? "" : "; kept from the command line:" + kept));
	}

	/** Small tier: GPU cores < 10 or RAM <= 8 GB, or either unreadable. Logs the tier and drops {@link #SMALL_OUT}. */
	private static void tier(Map<String, String> flags) {
		int cores = gpuCores();
		long mem = memBytes();
		boolean small = cores < 10 || mem <= 8L << 30;
		StringBuilder out = new StringBuilder();
		if (small) for (String k : SMALL_OUT) if (flags.remove(k) != null) out.append(' ').append(k);
		System.out.println("[cadence] profile alpha: tier " + (small ? "small" : "full") + " (gpu cores "
			+ (cores < 0 ? "unknown" : cores) + ", ram " + (mem < 0 ? "unknown" : String.format("%.1f GB", mem / (double) (1L << 30))) + ")"
			+ (out.isEmpty() ? "" : "; left out:" + out));
	}

	private static int gpuCores = -2;

	/** The GPU's core count from the IORegistry (AGXAccelerator "gpu-core-count"), -1 if unreadable; read once. */
	public static synchronized int gpuCores() {
		if (gpuCores == -2) {
			String s = run("/usr/sbin/ioreg", "-rd1", "-c", "AGXAccelerator");
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"gpu-core-count\"\\s*=\\s*(\\d+)").matcher(s);
			gpuCores = m.find() ? Integer.parseInt(m.group(1)) : -1;
		}
		return gpuCores;
	}

	private static long memBytes() {
		try {
			return Long.parseLong(run("/usr/sbin/sysctl", "-n", "hw.memsize").trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String run(String... cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
			byte[] b = p.getInputStream().readAllBytes();
			p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
			return new String(b, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	private static void writeDefault(Path cfg, String name) {
		String text = """
			# Cadence settings. Written on first launch; edit freely.
			#
			# The indie profile: Cadence's own terrain renderer on Apple Metal (no Sodium), plus measured startup and
			# thread-priority options. To turn all of it off (vanilla's renderer), change the next line to:  profile=none
			profile=%s
			#
			# Single switches win over the profile, e.g. our renderer off but the rest on:
			#cadence.own=false
			# Far terrain (EXPERIMENTAL, off by default; for Macs with 10 or more GPU cores): remove the # below.
			#cadence.lod=true
			""".formatted(name);
		try {
			Files.createDirectories(cfg.getParent());
			Files.writeString(cfg, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
			System.out.println("[cadence] profile: wrote " + cfg + " (profile=" + name + ")");
		} catch (IOException e) {
			System.out.println("[cadence] profile: can't write " + cfg + ": " + e);
		}
	}

	private static Path gameDir() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		} catch (Throwable t) {
			return Path.of("").toAbsolutePath();
		}
	}
}
