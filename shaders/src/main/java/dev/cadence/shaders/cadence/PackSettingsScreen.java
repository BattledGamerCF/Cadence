package dev.cadence.shaders.cadence;

import dev.cadence.shaders.pack.ShaderOption;
import dev.cadence.shaders.pack.ShaderPack;
import dev.cadence.shaders.runtime.PackEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

/**
 * Cadence: Shader Pack Settings, the pack's own options laid out the way its author arranged them (shaders.properties
 * screen= / screen.NAME= with [SUBSCREEN] pages, &lt;profile&gt; and &lt;empty&gt;), with the names and help from the pack's
 * lang/en_us.lang. Click a button for the next value, shift-click for the previous one. Settings are kept per pack in
 * shaderpacks/&lt;pack&gt;.txt, as Iris keeps them (so settings made in Iris carry over), and the pack reloads with them when
 * the settings screen is closed.
 */
public final class PackSettingsScreen extends OptionsSubScreen {
	/** What every page of one settings session shares: the pack, its values, and whether anything changed. */
	private static final class Session {
		final String packName;
		final ShaderPack pack;
		final Map<String, String> lang;
		final Map<String, String> props;
		/** Values set one by one (written to the pack's .txt); the profile is applied under them. */
		final Map<String, String> own = new TreeMap<>();
		String profile;
		boolean changed;

		Session(String packName, ShaderPack pack, Map<String, String> lang, Map<String, String> props) {
			this.packName = packName;
			this.pack = pack;
			this.lang = lang;
			this.props = props;
		}

		/** (the profile's expansion parses shaders.properties: computed once per change, not once per label) */
		private Map<String, String> cached;

		Map<String, String> values() {
			if (cached != null) return cached;
			Map<String, String> v = new TreeMap<>();
			try {
				if (!profile.isEmpty()) v.putAll(pack.profileOverrides(profile));
				v.putAll(own);
				cached = pack.optionValues(v);
			} catch (Exception e) {
				cached = Map.of();
			}
			return cached;
		}

		void changed() {
			cached = null;
			changed = true;
		}
	}

	private final Session session;
	private final String screenKey;
	private final boolean top;

	private PackSettingsScreen(Screen parent, Session session, String screenKey, Component title, boolean top) {
		super(parent, Minecraft.getInstance().options, title);
		this.session = session;
		this.screenKey = screenKey;
		this.top = top;
	}

	/** The settings of the pack picked in Video Settings, or null (with a log line) when it can't be read. */
	public static Screen open(Screen parent) {
		String name = ShaderToggle.selectedPack();
		if (name.isEmpty()) return null;
		try {
			ShaderPack pack = ShaderPack.load(ShaderToggle.packsDirectory().resolve(name));
			Map<String, String> lang = lang(pack);
			Session s = new Session(name, pack, lang, Map.of());
			s.profile = ShaderToggle.profileFor(name);
			if (!s.profile.isEmpty() && !ShaderToggle.profiles(name).contains(s.profile)) s.profile = "";  // (another pack's)
			s.own.putAll(readOwn(name));
			Map<String, String> props = pack.properties(s.values(), PackEnvironment.definitions()).values();
			Session session = new Session(name, pack, lang, props);
			session.profile = s.profile;
			session.own.putAll(s.own);
			return new PackSettingsScreen(parent, session, "screen", Component.literal(ShaderToggle.display(name) + " Settings"), true);
		} catch (Exception e) {
			LoggerFactory.getLogger("cadence_shaders").warn("Shader Pack Settings: can't read {}: {}", name, e.toString());
			return null;
		}
	}

	@Override
	protected void addOptions() {
		this.pageButtons.clear();
		this.optionButtons.clear();
		if (this.top) {
			int wrap = Math.max(120, Math.min(this.list.getRowWidth(), this.width - 24));
			for (Component line : CadenceSettings.wrap(Component.literal(
				"Click for the next value, shift-click for the previous. Changes apply when you leave.").withStyle(ChatFormatting.GRAY), wrap, this.font))
				this.list.addHeader(line);
		}
		String layout = this.session.props.get(this.screenKey);
		List<String> tokens = new ArrayList<>();
		if (layout != null && !layout.isBlank()) for (String t : layout.trim().split("\\s+")) tokens.add(t);
		else if (this.top) tokens.addAll(new TreeMap<>(this.session.pack.options()).keySet());  // (no layout from the author)
		// the author's grid (screen.columns / screen.NAME.columns, default 2): its <empty> spacers only line things up in 2 columns,
		// which is what this list shows; with another column count they'd land in the wrong places, so the entries just flow in pairs
		String columns = this.session.props.getOrDefault(this.screenKey + ".columns", "2").strip();  // (as OptiFine: each page its own, default 2)
		if (!columns.equals("2")) tokens.removeIf(t -> t.equals("<empty>"));
		Set<String> listed = new LinkedHashSet<>();
		for (String t : tokens) if (!t.startsWith("[") && !t.startsWith("<")) listed.add(t);
		List<AbstractWidget> row = new ArrayList<>();
		for (String t : tokens) {
			if (t.equals("*")) {
				for (String name : new TreeMap<>(this.session.pack.options()).keySet())
					if (!listed.contains(name)) this.place(row, this.optionWidget(name));
				continue;
			}
			// (a name that isn't an option keeps its slot empty so the author's columns stay aligned)
			this.place(row, t.equals("<empty>") ? null
				: t.equals("<profile>") ? this.profileButton()
				: t.startsWith("[") && t.endsWith("]") ? this.pageButton(t.substring(1, t.length() - 1))
				: this.session.pack.options().containsKey(t) ? this.optionWidget(t) : null);
		}
		if (!row.isEmpty()) this.addRow(row.get(0), null);
	}

	private void place(List<AbstractWidget> row, AbstractWidget w) {
		row.add(w);
		if (row.size() == 2) {
			this.addRow(row.get(0), row.get(1));
			row.clear();
		}
	}

	private void addRow(AbstractWidget left, AbstractWidget right) {
		if (left == null && right == null) return;  // (a blank row: the author's spacing, left out)
		if (left == null) this.list.addSmall(right, null);
		else this.list.addSmall(left, right);
	}

	/** Bottom row: Reset to Defaults (first page only) and Done, as vanilla's option screens. */
	@Override
	protected void addFooter() {
		var row = this.layout.addToFooter(net.minecraft.client.gui.layouts.LinearLayout.horizontal().spacing(8));
		if (this.top) row.addChild(Button.builder(Component.literal("Reset to Defaults"), b -> {
			this.session.own.clear();
			this.session.profile = "";
			this.session.changed();
			this.rebuildWidgets();
		}).width(150).tooltip(Tooltip.create(Component.literal("Every option back to the pack author's defaults, and no profile."))).build());
		row.addChild(Button.builder(net.minecraft.network.chat.CommonComponents.GUI_DONE, b -> this.onClose()).width(150).build());
	}

	private AbstractWidget pageButton(String page) {
		String label = this.session.lang.getOrDefault("screen." + page, page.replace('_', ' '));
		Button.Builder b = Button.builder(Component.literal(label + "§r..."), btn -> this.minecraft.gui.setScreen(
			new PackSettingsScreen(this, this.session, "screen." + page, Component.literal(strip(label)), false)));
		String help = this.session.lang.get("screen." + page + ".comment");
		if (help != null) b.tooltip(Tooltip.create(Component.literal(help)));
		Button built = b.build();
		this.pageButtons.add(built);
		return built;
	}

	private AbstractWidget profileButton() {
		List<String> profiles = new ArrayList<>();
		profiles.add("");
		profiles.addAll(ShaderToggle.profiles(this.session.packName));
		StringBuilder list = new StringBuilder();
		for (String p : profiles) list.append(list.length() == 0 ? "" : ", ").append(this.profileName(p));
		return Button.builder(this.profileLabel(), b -> {
			int i = profiles.indexOf(this.session.profile);
			int step = this.minecraft.hasShiftDown() ? profiles.size() - 1 : 1;
			this.session.profile = profiles.get(((i < 0 ? 0 : i) + step) % profiles.size());
			this.session.own.clear();  // a profile sets the options it names, as in Iris
			this.session.changed();
			this.rebuildWidgets();
		}).tooltip(Tooltip.create(Component.literal("The pack author's quality presets: " + list
			+ ". Choosing one sets its options; changing an option afterwards makes it Custom."))).build();
	}

	private String profileName(String p) {
		return p.isEmpty() ? "Default" : strip(this.session.lang.getOrDefault("profile." + p, p));
	}

	private Component profileLabel() {
		return Component.literal("Profile: " + this.profileName(this.session.profile) + (this.session.own.isEmpty() ? "" : " (Custom)"));
	}

	/** An option: a slider when the author lists it under sliders=, a plain line when it has one value, else a button. */
	private AbstractWidget optionWidget(String name) {
		ShaderOption option = this.session.pack.options().get(name);
		List<String> values = option.allowedValues();
		String help = this.session.lang.get("option." + name + ".comment");
		Tooltip tip = help != null ? Tips.of(help, "Default|" + this.valueText(name, option.defaultValue()))
			: Tips.of("Default: " + this.valueText(name, option.defaultValue()));
		if (values.size() <= 1) {
			// (an information line, like BSL's "BSL v10.1.8 by Capt Tatsu")
			Button info = Button.builder(Component.literal(this.session.lang.getOrDefault("option." + name, name.replace('_', ' '))), b -> { }).build();
			info.active = false;
			if (help != null) info.setTooltip(Tooltip.create(Component.literal(help)));
			return info;
		}
		AbstractWidget w;
		if (this.sliders().contains(name)) w = new OptionSlider(name, values);
		else {
			Button[] self = new Button[1];
			self[0] = Button.builder(this.optionLabel(name), b -> {
				String now = this.session.values().getOrDefault(name, option.defaultValue());
				int i = values.indexOf(now);
				int step = this.minecraft.hasShiftDown() ? values.size() - 1 : 1;
				this.set(name, values.get(((i < 0 ? 0 : i) + step) % values.size()));
				self[0].setMessage(this.optionLabel(name));
			}).build();
			w = self[0];
			this.optionButtons.add(self[0]);
		}
		w.setTooltip(tip);
		return w;
	}

	private void set(String name, String value) {
		this.session.own.put(name, value);
		this.session.changed();
	}

	private Set<String> sliders() {
		String s = this.session.props.get("sliders");
		Set<String> out = new LinkedHashSet<>();
		if (s != null) for (String t : s.trim().split("\\s+")) out.add(t);
		return out;
	}

	/** "Name: value", the pack's own colours in the name; a value changed from the default in yellow. */
	private Component optionLabel(String name) {
		ShaderOption option = this.session.pack.options().get(name);
		String value = this.session.values().getOrDefault(name, option.defaultValue());
		String label = this.session.lang.getOrDefault("option." + name, name.replace('_', ' '));
		var c = Component.literal(label + "§r: ");
		Component v = Component.literal(this.valueText(name, value));
		return c.append(value.equals(option.defaultValue()) ? v : v.copy().withStyle(ChatFormatting.YELLOW));
	}

	private String valueText(String name, String value) {
		String l = this.session.lang.get("value." + name + "." + value);
		if (l != null) return strip(l);
		ShaderOption option = this.session.pack.options().get(name);
		if (option != null && option.kind() == ShaderOption.Kind.SWITCH) return value.equals("true") ? "On" : "Off";
		return value;
	}

	/** A slider over an option's listed values (each a stop), as the author asked with sliders=. */
	private final class OptionSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
		private final String name;
		private final List<String> values;

		OptionSlider(String name, List<String> values) {
			super(0, 0, 150, 20, Component.empty(), 0);
			this.name = name;
			this.values = values;
			String now = PackSettingsScreen.this.session.values().getOrDefault(name, values.get(0));
			int i = Math.max(0, values.indexOf(now));
			this.value = values.size() <= 1 ? 0 : (double) i / (values.size() - 1);
			this.updateMessage();
		}

		private int index() {
			return (int) Math.round(this.value * (this.values.size() - 1));
		}

		@Override
		protected void updateMessage() {
			this.setMessage(PackSettingsScreen.this.sliderLabel(this.name, this.values.get(this.index())));
		}

		@Override
		protected void applyValue() {
			String v = this.values.get(this.index());
			if (!v.equals(PackSettingsScreen.this.session.values().get(this.name))) PackSettingsScreen.this.set(this.name, v);
		}
	}

	private Component sliderLabel(String name, String value) {
		ShaderOption option = this.session.pack.options().get(name);
		String label = this.session.lang.getOrDefault("option." + name, name.replace('_', ' '));
		Component v = Component.literal(this.valueText(name, value));
		return Component.literal(label + "§r: ").append(value.equals(option.defaultValue()) ? v : v.copy().withStyle(ChatFormatting.YELLOW));
	}

	/** Self-test: the Nth (1-based) page button / option button of the open settings page pressed. */
	static void pressPage(Screen screen, int n) {
		press(screen, n, true);
	}

	static void pressOption(Screen screen, int n) {
		press(screen, n, false);
	}

	private static void press(Screen screen, int n, boolean page) {
		if (!(screen instanceof PackSettingsScreen s)) return;
		List<Button> list = page ? s.pageButtons : s.optionButtons;
		if (n >= 1 && n <= list.size()) list.get(n - 1).onPress(null);
	}

	/** (self-test) this page's buttons, in layout order */
	private final List<Button> pageButtons = new ArrayList<>(), optionButtons = new ArrayList<>();

	@Override
	public void onClose() {
		if (this.top && this.session.changed) this.apply();
		super.onClose();
	}

	/** Saves the pack's own settings and its profile, and reloads the pack if it is the one running. */
	private void apply() {
		Path file = ShaderToggle.packsDirectory().resolve(Path.of(this.session.packName).getFileName().toString() + ".txt");
		try {
			if (this.session.own.isEmpty()) Files.deleteIfExists(file);
			else {
				Properties p = new Properties();
				this.session.own.forEach(p::setProperty);
				// (Properties' own byte format, as Iris writes this file)
				try (var out = Files.newOutputStream(file)) {
					p.store(out, "Shader pack settings (Cadence; the same file Iris uses)");
				}
			}
		} catch (IOException e) {
			LoggerFactory.getLogger("cadence_shaders").warn("Shader Pack Settings: can't save {}: {}", file, e.toString());
		}
		ShaderToggle.saveProfile(this.session.packName, this.session.profile);
		if (this.session.packName.equals(ShaderSwitcher.target())) ShaderSwitcher.reload();
	}

	static Map<String, String> readOwn(String packName) {
		Map<String, String> out = new HashMap<>();
		Path file = ShaderToggle.packsDirectory().resolve(Path.of(packName).getFileName().toString() + ".txt");
		if (!Files.isRegularFile(file)) return out;
		Properties p = new Properties();
		try (var in = Files.newInputStream(file)) {
			dev.cadence.metal.ConfigFiles.load(p, in);
		} catch (IOException | IllegalArgumentException e) {
			return out;
		}
		for (String k : p.stringPropertyNames()) out.put(k, p.getProperty(k).trim());
		return out;
	}

	/** lang/en_us.lang (the pack's names and help), plain key=value lines. */
	private static Map<String, String> lang(ShaderPack pack) {
		Map<String, String> out = new HashMap<>();
		for (String path : new String[] {"lang/en_us.lang", "lang/en_US.lang"}) {
			try {
				if (!pack.contains(path)) continue;
				for (String line : pack.text(path).split("\\R")) {
					int eq = line.indexOf('=');
					if (eq <= 0 || line.startsWith("#")) continue;
					out.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
				}
				break;
			} catch (Exception e) {
				// no names: option ids are shown
			}
		}
		return out;
	}

	/** Leaves §-formatting out of titles (button labels keep the pack's own colours). */
	private static String strip(String s) {
		return s.replaceAll("§.", "");
	}
}
