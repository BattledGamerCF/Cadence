package dev.cadence.shaders.cadence.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.cadence.shaders.cadence.shaderpack.BuiltinPacks;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Cadence: Create Shader (Shader Packs > Create Shader...). Your own shader, made from Cadence Lite: every setting with what it
 * does and what it costs, and a reference picture of one scene that follows your choices. The picture is put together from
 * reference shots taken with the real pack (one per feature, assets cadence/createshader/*.png: the scene with nothing on, and
 * with each feature on alone), each feature's difference added onto the plain one, then vibrance and contrast applied as the
 * pack applies them. Create writes &lt;name&gt;.zip (Cadence Lite) and its settings (&lt;name&gt;.zip.txt) into shaderpacks/ and
 * turns it on.
 */
public final class CreateShaderScreen extends Screen {
	/** One setting: the pack option, its values (as the pack writes them), how they read, what it does, cost per value. */
	record Setting(String key, String label, String[] values, String[] names, String description, int[] cost) {
	}

	private static final String[] ON_OFF = {"false", "true"}, OFF_ON_NAMES = {"Off", "On"};
	static final List<Setting> SETTINGS = List.of(
		new Setting("SHADOWS", "Shadows", ON_OFF, OFF_ON_NAMES,
			"Real shadows from the sun and moon. The biggest change to the look, and the biggest cost.", new int[] {0, 3}),
		new Setting("shadowMapResolution", "Shadow Detail", new String[] {"512", "1024", "2048", "3072", "4096"},
			new String[] {"Low", "Medium", "High", "Very High", "Ultra"},
			"How sharp shadow edges are. Higher uses more memory and GPU time. (Not visible in the small picture.)", new int[] {0, 1, 2, 3, 5}),
		new Setting("shadowDistance", "Shadow Distance", new String[] {"48.0", "64.0", "96.0", "128.0", "160.0", "192.0"},
			new String[] {"48", "64", "96", "128", "160", "192"},
			"How many blocks from you shadows reach. Further costs more, and spreads the shadow detail thinner.", new int[] {0, 0, 1, 1, 2, 2}),
		new Setting("SHADOW_FILTER", "Shadow Edges", new String[] {"0", "1", "2"}, new String[] {"Hard", "Smooth", "Soft"},
			"Hard edges are cheapest. Soft blurs them the way real sunlight does.", new int[] {0, 1, 2}),
		new Setting("SHADOW_STRENGTH", "Shadow Darkness", new String[] {"0.35", "0.45", "0.55", "0.65", "0.75"},
			new String[] {"35%", "45%", "55%", "65%", "75%"}, "How dark shadowed places get. Free.", new int[] {0, 0, 0, 0, 0}),
		new Setting("SUN_LIGHT", "Sunlight", new String[] {"0", "1", "2"}, new String[] {"Off", "Subtle", "Rich"},
			"A little warm light from the sun by day, and soft moonlight at night, on top of Minecraft's own light. Almost free.", new int[] {0, 0, 0}),
		new Setting("WATER", "Water", new String[] {"0", "1"}, new String[] {"Vanilla", "Reflective"},
			"Water reflects the sky, with small waves and a glint of sun. Cheap.", new int[] {0, 1}),
		new Setting("WATER_SSR", "World Reflections", ON_OFF, OFF_ON_NAMES,
			"Water also reflects the land and trees around it (needs Reflective water). Costs more on big lakes and oceans.", new int[] {0, 2}),
		new Setting("WAVING", "Waving Plants", ON_OFF, OFF_ON_NAMES,
			"Leaves, grass, flowers and crops move in the wind. Almost free. (Not visible in a still picture.)", new int[] {0, 0}),
		new Setting("BLOOM", "Bloom", ON_OFF, OFF_ON_NAMES, "Bright lights and the sky glow softly into what's around them.", new int[] {0, 1}),
		new Setting("BLOOM_STRENGTH", "Bloom Strength", new String[] {"0.06", "0.09", "0.12", "0.16", "0.20"},
			new String[] {"Faint", "Light", "Medium", "Strong", "Dreamy"}, "How strong the glow is. Free.", new int[] {0, 0, 0, 0, 0}),
		new Setting("GODRAYS", "Light Shafts", ON_OFF, OFF_ON_NAMES,
			"Rays of light from the sun and moon when they're on screen, broken up by trees and hills.", new int[] {0, 2}),
		new Setting("TONEMAP", "Highlight Roll-off", ON_OFF, OFF_ON_NAMES,
			"Bright skies and lights fade smoothly instead of clipping to flat white. With HDR they use the screen's extra brightness. Free.",
			new int[] {0, 0}),
		new Setting("VIBRANCE", "Vibrance", new String[] {"1.00", "1.04", "1.08", "1.14", "1.20", "1.28"},
			new String[] {"Vanilla", "+4%", "+8%", "+14%", "+20%", "+28%"},
			"How colourful the picture is. Muted colours are lifted more than strong ones, so it stays natural. Free.", new int[] {0, 0, 0, 0, 0, 0}),
		new Setting("CONTRAST", "Contrast", new String[] {"1.00", "1.02", "1.05", "1.08"}, new String[] {"Vanilla", "+2%", "+5%", "+8%"},
			"Darker darks and brighter brights. Free.", new int[] {0, 0, 0, 0}));

	private static final String[] PRESETS = {"LOW", "MEDIUM", "HIGH", "ULTRA"};
	private static final String[] LAYERS = {"base", "shadows", "soft", "sun1", "sun2", "water", "ssr", "bloom", "godrays", "tonemap"};
	private static final Identifier PREVIEW = Identifier.fromNamespaceAndPath("cadence_shaders", "dynamic/create_shader_preview");

	private final Screen parent;
	private final Map<String, String> values = new LinkedHashMap<>();
	private final Map<String, int[]> layers = new HashMap<>();
	private final List<Button> buttons = new ArrayList<>();
	private int preset = 1;
	private int imgW, imgH;
	private DynamicTexture texture;
	private Setting described;
	private EditBox name;
	private Button presetButton;
	private String status = "";

	public CreateShaderScreen(Screen parent) {
		super(dev.cadence.metal.system.Lang.literal("Create Shader"));
		this.parent = parent;
		this.applyPreset(PRESETS[this.preset]);
		this.loadLayers();
	}

	/** The Shader Packs screen's button. */
	public static Button button(Screen parent) {
		return Button.builder(dev.cadence.metal.system.Lang.literal("Create Shader..."), b -> Minecraft.getInstance().gui.setScreen(new CreateShaderScreen(parent)))
			.width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Make your own shader from Cadence Lite: pick what it does, see a reference picture, and save it as a new pack.")).build();
	}

	// ------------------------------------------------------------------ settings

	private void applyPreset(String profile) {
		// the pack's own defaults first (lib/settings.glsl), then the preset on top
		this.values.putAll(builtinDefaults());
		// the pack's own defaults and profile, read from the built-in pack (one source of truth)
		Map<String, String> props = builtinProperties();
		String line = props.getOrDefault("profile." + profile, "");
		for (String token : line.trim().split("\\s+")) {
			if (token.isEmpty()) continue;
			if (token.startsWith("!")) this.values.put(token.substring(1), "false");
			else if (token.contains(":")) this.values.put(token.substring(0, token.indexOf(':')), token.substring(token.indexOf(':') + 1));
			else this.values.put(token, "true");
		}
		for (Setting s : SETTINGS) if (!List.of(s.values()).contains(this.values.get(s.key()))) this.values.put(s.key(), s.values()[0]);
	}

	/** The pack's defaults: "#define X" (on), "//#define X" (off), "#define X 1.0", "const ... X = 1.0;". */
	private static Map<String, String> builtinDefaults() {
		Map<String, String> out = new HashMap<>();
		String text = builtinFile("shaders/lib/settings.glsl");
		for (String raw : text.split("\n")) {
			String l = raw.strip();
			var m = java.util.regex.Pattern.compile("^(//)?\\s*#define\\s+(\\w+)(?:\\s+([^\\s/]+))?").matcher(l);
			if (m.find()) {
				if (m.group(3) == null) out.put(m.group(2), m.group(1) == null ? "true" : "false");
				else if (m.group(1) == null) out.put(m.group(2), m.group(3));
				continue;
			}
			var c = java.util.regex.Pattern.compile("^const\\s+\\w+\\s+(\\w+)\\s*=\\s*([^;]+);").matcher(l);
			if (c.find()) out.put(c.group(1), c.group(2).strip());
		}
		return out;
	}

	private static String builtinFile(String name) {
		try (InputStream in = CreateShaderScreen.class.getResourceAsStream("/cadence/cadence-lite.zip")) {
			if (in == null) return "";
			var zip = new java.util.zip.ZipInputStream(in);
			for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry())
				if (e.getName().equals(name)) return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (IOException e) {
			// none
		}
		return "";
	}

	private static Map<String, String> builtinProperties() {
		Map<String, String> out = new HashMap<>();
		try (InputStream in = CreateShaderScreen.class.getResourceAsStream("/cadence/cadence-lite.zip")) {
			if (in == null) return out;
			var zip = new java.util.zip.ZipInputStream(in);
			for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
				if (!e.getName().equals("shaders/shaders.properties")) continue;
				for (String l : new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
					int eq = l.indexOf('=');
					if (eq > 0 && !l.startsWith("#")) out.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
				}
			}
		} catch (IOException e) {
			// presets fall back to the first values
		}
		return out;
	}

	private int index(Setting s) {
		return Math.max(0, List.of(s.values()).indexOf(this.values.get(s.key())));
	}

	private boolean on(String key) {
		return "true".equals(this.values.get(key));
	}

	private double num(String key) {
		try {
			return Double.parseDouble(this.values.get(key));
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/** Settings that do nothing with the others as they are (shadow detail without shadows...): greyed out. */
	private boolean active(Setting s) {
		return switch (s.key()) {
			case "shadowMapResolution", "shadowDistance", "SHADOW_FILTER", "SHADOW_STRENGTH" -> this.on("SHADOWS");
			case "WATER_SSR" -> !"0".equals(this.values.get("WATER"));
			case "BLOOM_STRENGTH" -> this.on("BLOOM");
			default -> true;
		};
	}

	private int cost() {
		int c = 0;
		for (Setting s : SETTINGS) if (this.active(s)) c += s.cost()[this.index(s)];
		return c;
	}

	private String costText() {
		int c = this.cost();
		if (c <= 1) return "Cost: very light (any Mac, including the smallest)";
		if (c <= 5) return "Cost: light (MacBook Air and up)";
		if (c <= 10) return "Cost: medium (M-series Pro and up for high frame rates)";
		return "Cost: heavy (a showcase for Max and Ultra chips)";
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		this.buttons.clear();
		int left = this.leftWidth(), colW = Math.max(80, (left - 12) / 2), x0 = 8, y0 = 30;
		// (rows packed closer on a short screen: they end above the status line and the preset button, BH11-09)
		int rows = (SETTINGS.size() + 1) / 2, rowH = Math.max(14, Math.min(22, (this.height - 28 - 24 - 14 - y0) / rows));
		for (int i = 0; i < SETTINGS.size(); i++) {
			Setting s = SETTINGS.get(i);
			int x = x0 + (i % 2) * (colW + 4), y = y0 + (i / 2) * rowH;
			Button b = Button.builder(Component.empty(), btn -> {
				int step = this.minecraft.hasShiftDown() ? s.values().length - 1 : 1;
				this.values.put(s.key(), s.values()[(this.index(s) + step) % s.values().length]);
				this.described = s;
				this.refresh();
			}).bounds(x, y, colW, Math.min(20, rowH - 2)).build();
			this.buttons.add(this.addRenderableWidget(b));
		}
		int bottom = this.height - 28;
		this.presetButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.preset = (this.preset + 1) % PRESETS.length;
			this.applyPreset(PRESETS[this.preset]);
			this.refresh();
		}).bounds(8, bottom - 24, colW * 2 + 4, 20).tooltip(Tips.of("Start from one of Cadence Lite's presets, then change what you like.")).build());
		this.name = this.addRenderableWidget(new EditBox(this.font, 8, bottom, colW, 20, dev.cadence.metal.system.Lang.literal("Name")));
		this.name.setMaxLength(40);
		if (this.name.getValue().isEmpty()) this.name.setValue("My Shader");
		this.addRenderableWidget(Button.builder(dev.cadence.metal.system.Lang.literal("Create"), b -> this.create())
			.bounds(8 + colW + 4, bottom, (colW - 4) / 2 + 2, 20).build());
		this.addRenderableWidget(Button.builder(dev.cadence.metal.system.Lang.literal("Cancel"), b -> this.onClose())
			.bounds(8 + colW + 4 + (colW - 4) / 2 + 6, bottom, (colW - 4) / 2 - 2, 20).build());
		this.refresh();
	}

	/** The settings side: most of the width (the labels are long), the picture gets the rest. */
	private int leftWidth() {
		return Math.min(420, this.width * 58 / 100);
	}

	private void refresh() {
		for (int i = 0; i < SETTINGS.size(); i++) {
			Setting s = SETTINGS.get(i);
			Button b = this.buttons.get(i);
			b.setMessage(dev.cadence.metal.system.Lang.literal(s.label() + ": " + s.names()[this.index(s)]));
			b.active = this.active(s);
		}
		if (this.presetButton != null)
			this.presetButton.setMessage(dev.cadence.metal.system.Lang.literal("Start from: " + PRESETS[this.preset].charAt(0) + PRESETS[this.preset].substring(1).toLowerCase(Locale.ROOT)));
		this.compose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		g.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
		int px = this.leftWidth() + 8, pw = this.width - px - 8;
		Setting hovered = this.described;
		for (int i = 0; i < SETTINGS.size(); i++) if (this.buttons.get(i).isHovered()) hovered = SETTINGS.get(i);
		if (this.texture != null && this.imgW > 0) {
			int ph = pw * this.imgH / this.imgW;
			g.fill(px - 1, 29, px + pw + 1, 30 + ph + 1, 0xFF4A4A5A);
			g.blit(RenderPipelines.GUI_TEXTURED, PREVIEW, px, 30, 0, 0, pw, ph, pw, ph);
			int y = 30 + ph + 6;
			g.text(this.font, "Reference picture", px, y, 0xFF9A9AA8);
			y += 14;
			g.text(this.font, this.costText(), px, y, this.cost() > 10 ? 0xFFE8D880 : 0xFF7CE08A);
			y += 16;
			if (hovered != null) {
				g.text(this.font, hovered.label(), px, y, 0xFFFFFFFF);
				g.textWithWordWrap(this.font, dev.cadence.metal.system.Lang.literal(hovered.description()), px, y + 12, pw, 0xFFD0D0DA);
			}
		} else {
			g.text(this.font, "No reference picture in this build.", px, 30, 0xFF9A9AA8);
		}
		if (!this.status.isEmpty()) g.text(this.font, this.status, 8, this.height - 64 + 2, this.status.startsWith("Couldn") ? 0xFFE88080 : 0xFF7CE08A);
	}

	// ------------------------------------------------------------------ the reference picture

	private void loadLayers() {
		for (String l : LAYERS) {
			try (InputStream in = CreateShaderScreen.class.getResourceAsStream("/cadence/createshader/" + l + ".png")) {
				if (in == null) continue;
				try (NativeImage img = NativeImage.read(in)) {
					this.imgW = img.getWidth();
					this.imgH = img.getHeight();
					int[] px = new int[this.imgW * this.imgH];
					for (int y = 0; y < this.imgH; y++) for (int x = 0; x < this.imgW; x++) px[y * this.imgW + x] = img.getPixel(x, y);
					this.layers.put(l, px);
				}
			} catch (IOException | RuntimeException e) {
				// that layer left out
			}
		}
		if (!this.layers.containsKey("base")) this.imgW = 0;
	}

	/** The picture for the current settings: the plain shot plus each chosen feature's difference, then the colour grade. */
	private void compose() {
		if (this.imgW == 0) return;
		int n = this.imgW * this.imgH;
		int[] base = this.layers.get("base");
		List<int[]> add = new ArrayList<>(), sub = new ArrayList<>();
		List<Double> weight = new ArrayList<>();
		java.util.function.BiConsumer<String, Double> feature = (layer, w) -> {
			if (this.layers.containsKey(layer)) {
				add.add(this.layers.get(layer));
				sub.add(base);
				weight.add(w);
			}
		};
		if (this.on("SHADOWS")) {
			feature.accept("shadows", this.num("SHADOW_STRENGTH") / 0.55);
			if ("2".equals(this.values.get("SHADOW_FILTER")) && this.layers.containsKey("soft") && this.layers.containsKey("shadows")) {
				add.add(this.layers.get("soft"));
				sub.add(this.layers.get("shadows"));
				weight.add(1.0);
			}
		}
		if ("1".equals(this.values.get("SUN_LIGHT"))) feature.accept("sun1", 1.0);
		if ("2".equals(this.values.get("SUN_LIGHT"))) feature.accept("sun2", 1.0);
		if ("1".equals(this.values.get("WATER"))) {
			feature.accept("water", 1.0);
			if (this.on("WATER_SSR") && this.layers.containsKey("ssr") && this.layers.containsKey("water")) {
				add.add(this.layers.get("ssr"));
				sub.add(this.layers.get("water"));
				weight.add(1.0);
			}
		}
		if (this.on("BLOOM")) feature.accept("bloom", this.num("BLOOM_STRENGTH") / 0.12);
		if (this.on("GODRAYS")) feature.accept("godrays", 1.0);
		if (this.on("TONEMAP")) feature.accept("tonemap", 1.0);
		double vib = this.num("VIBRANCE"), con = this.num("CONTRAST");
		NativeImage out = new NativeImage(this.imgW, this.imgH, false);
		for (int i = 0; i < n; i++) {
			double[] c = rgb(base[i]);
			for (int k = 0; k < add.size(); k++) {
				double[] a = rgb(add.get(k)[i]), b = rgb(sub.get(k)[i]);
				double w = weight.get(k);
				for (int ch = 0; ch < 3; ch++) c[ch] += (a[ch] - b[ch]) * w;
			}
			// the pack's final.fsh grade
			double l = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
			double sat = Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2]));
			double f = 1 + (vib - 1) * (1.6 - sat);
			for (int ch = 0; ch < 3; ch++) c[ch] = Math.clamp(((l + (c[ch] - l) * f) - 0.5) * con + 0.5, 0, 1);
			out.setPixel(i % this.imgW, i / this.imgW, 0xFF000000 | (int) (c[2] * 255 + 0.5) << 16 | (int) (c[1] * 255 + 0.5) << 8 | (int) (c[0] * 255 + 0.5));
		}
		if (this.texture == null) {
			this.texture = new DynamicTexture(() -> "Create Shader preview", out);
			Minecraft.getInstance().getTextureManager().register(PREVIEW, this.texture);
		} else {
			this.texture.setPixels(out);
			this.texture.upload();
		}
	}

	/** NativeImage pixels are ABGR. */
	private static double[] rgb(int abgr) {
		return new double[] {(abgr & 0xFF) / 255.0, (abgr >> 8 & 0xFF) / 255.0, (abgr >> 16 & 0xFF) / 255.0};
	}

	// ------------------------------------------------------------------ create

	private void create() {
		try {
			Map<String, String> only = new LinkedHashMap<>();
			for (Setting st : SETTINGS) only.put(st.key(), this.values.get(st.key()));
			String file = write(this.name.getValue(), only);
			this.status = "Created " + file + " and turned it on.";
			Minecraft.getInstance().gui.setScreen(this.parent);
		} catch (IOException | RuntimeException e) {
			this.status = "Couldn't create it: " + e.getMessage();
		}
	}

	/** Writes &lt;name&gt;.zip (Cadence Lite) and its settings into shaderpacks/, and turns it on. The pack's file name. */
	static String write(String name, Map<String, String> values) throws IOException {
		String n = name.strip().replaceAll("[\\\\/:*?\"<>|]", "");
		if (n.isEmpty()) n = "My Shader";
		Path dir = ShaderToggle.packsDirectory();
		// (never over another pack or the built-in one's settings: macOS names ignore case, BH11-09)
		String file = n + ".zip";
		if (file.equalsIgnoreCase(BuiltinPacks.CADENCE_LITE)) file = n + " (mine).zip";
		for (int k = 2; Files.exists(dir.resolve(file)) || file.equalsIgnoreCase(BuiltinPacks.CADENCE_LITE); k++) file = n + " (" + k + ").zip";
		try (InputStream in = CreateShaderScreen.class.getResourceAsStream("/cadence/cadence-lite.zip")) {
			if (in == null) throw new IOException("Cadence Lite isn't in this build");
			Files.createDirectories(dir);
			Files.write(dir.resolve(file), in.readAllBytes());
		}
		Properties p = new Properties();
		values.forEach(p::setProperty);
		dev.cadence.metal.system.ConfigFiles.store(p, dir.resolve(file + ".txt"), "Made with Cadence's Create Shader (Cadence Lite settings)", false);
		ShaderToggle.saveProfile(file, "");
		ShaderToggle.choose(file);
		return file;
	}

	/** Self-test (makeshader=NAME:PRESET): a shader made from a preset, without the screen. */
	public static String makeForTest(String name, String preset) throws IOException {
		CreateShaderScreen screen = new CreateShaderScreen(null);
		screen.applyPreset(preset.toUpperCase(Locale.ROOT));
		Map<String, String> only = new LinkedHashMap<>();
		for (Setting st : SETTINGS) only.put(st.key(), screen.values.get(st.key()));
		return write(name, only);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(this.parent);
	}

	@Override
	public void removed() {
		super.removed();
		if (this.texture != null) {
			Minecraft.getInstance().getTextureManager().release(PREVIEW);
			this.texture = null;
		}
	}
}
