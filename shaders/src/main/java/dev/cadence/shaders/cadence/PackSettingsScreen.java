package dev.cadence.shaders.cadence;

import dev.cadence.shaders.pack.ShaderOption;
import dev.cadence.shaders.pack.ShaderPack;
import dev.cadence.shaders.runtime.PackEnvironment;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
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

		Map<String, String> values() {
			Map<String, String> v = new TreeMap<>();
			try {
				if (!profile.isEmpty()) v.putAll(pack.profileOverrides(profile));
				v.putAll(own);
				return pack.optionValues(v);
			} catch (Exception e) {
				return Map.of();
			}
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
			s.profile = ShaderToggle.configValue("profile", "").strip();
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
		if (this.top) {
			this.list.addSmall(Button.builder(Component.literal("Reset to Defaults"), b -> {
				this.session.own.clear();
				this.session.profile = "";
				this.session.changed = true;
				this.rebuildWidgets();
			}).tooltip(Tooltip.create(Component.literal("Every option back to the pack author's default, and no profile."))).build(), null);
		}
		this.pageButtons.clear();
		this.optionButtons.clear();
		String layout = this.session.props.get(this.screenKey);
		List<String> tokens = new ArrayList<>();
		if (layout != null && !layout.isBlank()) for (String t : layout.trim().split("\\s+")) tokens.add(t);
		else if (this.top) {
			// no layout from the author: every option, by name
			tokens.addAll(new TreeMap<>(this.session.pack.options()).keySet());
		}
		Set<String> listed = new LinkedHashSet<>();
		for (String t : tokens) if (!t.startsWith("[") && !t.startsWith("<")) listed.add(t);
		List<AbstractWidget> row = new ArrayList<>();
		for (String t : tokens) {
			if (t.equals("*")) {
				for (String name : new TreeMap<>(this.session.pack.options()).keySet()) if (!listed.contains(name)) row.add(this.optionButton(name));
				continue;
			}
			AbstractWidget w = t.equals("<empty>") ? null
				: t.equals("<profile>") ? this.profileButton()
				: t.startsWith("[") && t.endsWith("]") ? this.pageButton(t.substring(1, t.length() - 1))
				: this.session.pack.options().containsKey(t) ? this.optionButton(t) : null;
			// (a name that isn't an option, like BSL's ABOUT, keeps its slot empty so the author's columns stay aligned)
			row.add(w);
			if (row.size() == 2) {
				this.addRow(row.get(0), row.get(1));
				row.clear();
			}
		}
		if (!row.isEmpty()) this.addRow(row.get(0), null);
	}

	private void addRow(AbstractWidget left, AbstractWidget right) {
		if (left == null && right == null) return;  // (a blank row: the author's spacing, left out)
		if (left == null) this.list.addSmall(right, null);
		else this.list.addSmall(left, right);
	}

	private AbstractWidget pageButton(String page) {
		String label = this.session.lang.getOrDefault("screen." + page, page.replace('_', ' '));
		Button.Builder b = Button.builder(Component.literal(label + "..."), btn -> this.minecraft.gui.setScreen(
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
		Button[] self = new Button[1];
		self[0] = Button.builder(this.profileLabel(), b -> {
			int i = profiles.indexOf(this.session.profile);
			int step = this.minecraft.hasShiftDown() ? profiles.size() - 1 : 1;
			this.session.profile = profiles.get(((i < 0 ? 0 : i) + step) % profiles.size());
			this.session.own.clear();  // a profile sets the options it names, as in Iris
			this.session.changed = true;
			this.rebuildWidgets();
		}).tooltip(Tooltip.create(Component.literal("The pack author's quality presets. Choosing one sets its options; changing an option after that makes it Custom."))).build();
		return self[0];
	}

	private Component profileLabel() {
		String p = this.session.profile;
		String name = p.isEmpty() ? "Default" : this.session.lang.getOrDefault("profile." + p, p);
		boolean custom = !this.session.own.isEmpty();
		return Component.literal("Profile: " + strip(name) + (custom ? " (Custom)" : ""));
	}

	private AbstractWidget optionButton(String name) {
		ShaderOption option = this.session.pack.options().get(name);
		List<String> values = option.allowedValues();
		Button[] self = new Button[1];
		self[0] = Button.builder(this.optionLabel(name), b -> {
			String now = this.session.values().getOrDefault(name, option.defaultValue());
			int i = values.indexOf(now);
			int step = this.minecraft.hasShiftDown() ? values.size() - 1 : 1;
			String next = values.get(((i < 0 ? 0 : i) + step) % values.size());
			this.session.own.put(name, next);
			this.session.changed = true;
			self[0].setMessage(this.optionLabel(name));
		}).build();
		String help = this.session.lang.get("option." + name + ".comment");
		self[0].setTooltip(Tooltip.create(Component.literal((help != null ? help + "\n" : "")
			+ "Default: " + this.valueText(name, option.defaultValue()) + ". Shift-click for the previous value.")));
		this.optionButtons.add(self[0]);
		return self[0];
	}

	private Component optionLabel(String name) {
		ShaderOption option = this.session.pack.options().get(name);
		String value = this.session.values().getOrDefault(name, option.defaultValue());
		String label = strip(this.session.lang.getOrDefault("option." + name, name.replace('_', ' ')));
		Component c = Component.literal(label + ": " + this.valueText(name, value));
		return value.equals(option.defaultValue()) ? c : c.copy().withStyle(ChatFormatting.YELLOW);
	}

	private String valueText(String name, String value) {
		String l = this.session.lang.get("value." + name + "." + value);
		if (l != null) return strip(l);
		ShaderOption option = this.session.pack.options().get(name);
		if (option != null && option.kind() == ShaderOption.Kind.SWITCH) return value.equals("true") ? "On" : "Off";
		return value;
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
				try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
					p.store(w, "Shader pack settings (Cadence; the same file Iris uses)");
				}
			}
		} catch (IOException e) {
			LoggerFactory.getLogger("cadence_shaders").warn("Shader Pack Settings: can't save {}: {}", file, e.toString());
		}
		ShaderToggle.saveKey("profile", this.session.profile);
		if (this.session.packName.equals(ShaderSwitcher.active())) ShaderSwitcher.reload();
	}

	static Map<String, String> readOwn(String packName) {
		Map<String, String> out = new HashMap<>();
		Path file = ShaderToggle.packsDirectory().resolve(Path.of(packName).getFileName().toString() + ".txt");
		if (!Files.isRegularFile(file)) return out;
		Properties p = new Properties();
		try (var r = Files.newBufferedReader(file)) {
			p.load(r);
		} catch (IOException e) {
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
