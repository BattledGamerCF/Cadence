package dev.cadence.shaders.cadence.ui;

import dev.cadence.shaders.cadence.shaderpack.PackCatalog;
import dev.cadence.shaders.cadence.shaderpack.ShaderSwitcher;

import dev.cadence.shaders.CadencePackGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.LoggerFactory;

/**
 * Cadence: Shader Packs (Video Settings, the Shaders button), every installed pack in one list. Clicking a pack turns it on
 * right away (it's prepared in the background while the world keeps drawing); Off turns shaders off. Each pack says whether
 * it has been tested with Cadence and how heavy it is (PackCatalog), or why it failed to load this session. Packs are added
 * by dropping their .zip files on the window (or with the folder button). Profile and Pack Settings act on the selected pack.
 */
public final class ShaderPacksScreen extends OptionsSubScreen {
	private static final Component HINT = Component.literal("Click a pack to turn it on. Drop .zip files here to add packs.")
		.withStyle(ChatFormatting.GRAY);
	private PackList packs;
	private StringWidget hint;
	private Button profileButton, settingsButton;

	public ShaderPacksScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.literal("Shader Packs"));
	}

	@Override
	protected void addTitle() {
		this.layout.setHeaderHeight(44);
		LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(5));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(this.title, this.font));
		this.hint = header.addChild(new StringWidget(HINT, this.font));
	}

	@Override
	protected void addContents() {
		this.layout.setFooterHeight(58);
		this.packs = this.layout.addToContents(new PackList(this.minecraft, this.width, this.layout.getContentHeight(), this.layout.getHeaderHeight()));
		this.packs.fill(ShaderToggle.selectedPack());
	}

	@Override
	protected void addOptions() {
	}

	@Override
	protected void addFooter() {
		LinearLayout rows = this.layout.addToFooter(LinearLayout.vertical().spacing(4));
		LinearLayout top = rows.addChild(LinearLayout.horizontal().spacing(8));
		this.profileButton = top.addChild(Button.builder(Component.empty(), b -> this.cycleProfile())
			.width(Button.DEFAULT_WIDTH).tooltip(Tips.of(Tips.Impact.VARIES, "The pack author's quality presets.", "Lower|faster: simpler shadows and lighting",
				"Higher|nicer, slower", "#Shift-click for the previous one. Your own changes in Pack Settings stay on top.")).build());
		this.settingsButton = top.addChild(Button.builder(Component.literal("Pack Settings..."), b -> this.openSettings())
			.width(Button.DEFAULT_WIDTH).tooltip(Tips.of("The selected pack's own options (sun, clouds, water, shadows...).")).build());
		LinearLayout bottom = rows.addChild(LinearLayout.horizontal().spacing(8));
		bottom.addChild(Button.builder(Component.literal("Open Packs Folder..."), b -> ShaderToggle.openPacksFolder())
			.width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Opens the shaderpacks folder in Finder.",
				"#Packs are .zip files (BSL, Complementary...). Leave them zipped.")).build());
		bottom.addChild(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).width(Button.DEFAULT_WIDTH).build());
		this.updateButtons();
	}

	@Override
	protected void repositionElements() {
		super.repositionElements();
		if (this.packs != null) this.packs.updateSize(this.width, this.layout);
	}

	/** The pack picked in the list ("" for Off). */
	private String selected() {
		PackEntry e = this.packs == null ? null : this.packs.getSelected();
		return e != null && e.pack != null ? e.pack : ShaderToggle.selectedPack();
	}

	private void choose(String pack) {
		ShaderToggle.choose(pack);
		this.updateButtons();
	}

	private void updateButtons() {
		if (this.profileButton == null) return;
		String pack = this.selected();
		boolean on = !pack.isEmpty();
		this.settingsButton.active = on;
		List<String> profiles = on ? ShaderToggle.profiles(pack) : List.of();
		this.profileButton.active = !profiles.isEmpty();
		String now = ShaderToggle.profileFor(pack);
		if (!profiles.contains(now)) now = "";
		this.profileButton.setMessage(Component.literal("Profile: " + (!on ? "-" : profiles.isEmpty() ? "None" : pretty(now))));
	}

	private void cycleProfile() {
		String pack = this.selected();
		List<String> list = new ArrayList<>();
		list.add("");
		list.addAll(ShaderToggle.profiles(pack));
		String now = ShaderToggle.profileFor(pack);
		int i = Math.max(0, list.indexOf(now));
		int step = this.minecraft.hasShiftDown() ? list.size() - 1 : 1;
		ShaderToggle.saveProfile(pack, list.get((i + step) % list.size()));
		if (pack.equals(ShaderSwitcher.target())) ShaderSwitcher.reload();
		this.updateButtons();
	}

	private void openSettings() {
		Screen next = PackSettingsScreen.open(this);
		if (next != null) this.minecraft.gui.setScreen(next);
		else this.say(Component.literal("Can't read this pack's settings (see the log). The pack may still work.").withStyle(ChatFormatting.RED));
	}

	private static String pretty(String profile) {
		if (profile.isEmpty()) return "Default";
		String p = profile.replace('_', ' ').toLowerCase(Locale.ROOT);
		return Character.toUpperCase(p.charAt(0)) + p.substring(1);
	}

	private void say(Component message) {
		if (this.hint != null) {
			this.hint.setMessage(message);
			this.repositionElements();
		}
	}

	/** Shader packs dropped on the window: copied into shaderpacks/ and the first one turned on. */
	@Override
	public void onFilesDrop(List<Path> paths) {
		Path dir = ShaderToggle.packsDirectory();
		List<String> added = new ArrayList<>(), refused = new ArrayList<>(), updated = new ArrayList<>();
		for (Path p : paths) {
			String name = p.getFileName().toString();
			if (!isPack(p)) {
				refused.add(name);
				continue;
			}
			try {
				Files.createDirectories(dir);
				Path target = dir.resolve(name);
				// (a zip of the same name but different content is a newer version: it replaces the old one; the same file is left)
				boolean existed = Files.exists(target);
				if (!existed || !Files.isDirectory(p) && copyChanged(p, target)) {
					if (!existed) copy(p, target);
					else updated.add(name);
				}
				added.add(name);
			} catch (IOException e) {
				LoggerFactory.getLogger("cadence_shaders").warn("Shader Packs: can't copy {}: {}", p, e.toString());
				refused.add(name);
			}
		}
		if (!added.isEmpty()) {
			this.packs.fill(added.get(0));
			// (the pack in use was replaced by a newer file: reloaded, as choosing it again would do nothing)
			if (updated.contains(added.get(0)) && added.get(0).equals(ShaderSwitcher.target())) ShaderSwitcher.reload();
			else this.choose(added.get(0));
		}
		if (!refused.isEmpty()) this.say(Component.literal(String.join(", ", refused.stream().map(ShaderToggle::display).toList())
			+ (refused.size() == 1 ? " isn't a shader pack" : " aren't shader packs") + " (no shaders folder inside).").withStyle(ChatFormatting.RED));
		else if (!added.isEmpty()) this.say(Component.literal((updated.size() == added.size() ? "Updated " : "Added ")
			+ String.join(", ", added.stream().map(ShaderToggle::display).toList()) + ".")
			.withStyle(ChatFormatting.GREEN));
	}

	/** A zip's shaders folder at its top or one folder down (some packs are zipped inside a folder); a resource pack's
	 * assets/minecraft/shaders/ is deeper, so a resource pack dropped here by mistake is refused; macOS's __MACOSX copies don't count. */
	private static final java.util.regex.Pattern PACK_ROOT = java.util.regex.Pattern.compile("^(?!__MACOSX/)([^/]+/)?shaders/");

	/** A folder with a shaders folder, or a .zip with one at its top or one folder down. */
	static boolean isPack(Path p) {
		if (Files.isDirectory(p)) return Files.isDirectory(p.resolve("shaders"));
		if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) return false;
		try (var zip = new java.util.zip.ZipFile(p.toFile())) {
			return zip.stream().anyMatch(e -> PACK_ROOT.matcher(e.getName().replace('\\', '/')).find());
		} catch (IOException e) {
			return false;
		}
	}

	/** A zip copied over an existing one when their contents differ (via a temporary file, then moved in whole); false if same. */
	private static boolean copyChanged(Path from, Path to) throws IOException {
		if (Files.size(from) == Files.size(to) && Files.mismatch(from, to) == -1) return false;
		copy(from, to);
		return true;
	}

	private static void copy(Path from, Path to) throws IOException {
		if (!Files.isDirectory(from)) {
			// (into a temporary file first: a copy cut short, a full disk, left a partial zip that was never replaced)
			Path tmp = Files.createTempFile(to.getParent(), ".drop-", ".tmp");
			try {
				Files.copy(from, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				Files.move(tmp, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} finally {
				Files.deleteIfExists(tmp);
			}
			return;
		}
		try (Stream<Path> all = Files.walk(from)) {
			for (Path p : (Iterable<Path>) all::iterator) {
				Path t = to.resolve(from.relativize(p).toString());
				if (Files.isDirectory(p)) Files.createDirectories(t);
				else Files.copy(p, t);
			}
		}
	}

	/** Self-test: the first pack in the open list whose name starts with {@code name} ("off": Off) clicked; what was picked. */
	public static String pick(Screen screen, String name) {
		if (!(screen instanceof ShaderPacksScreen s)) return "no Shader Packs screen";
		for (PackEntry e : s.packs.children()) {
			if (e.pack == null) continue;
			String shown = e.pack.isEmpty() ? "off" : ShaderToggle.display(e.pack).toLowerCase(Locale.ROOT);
			if (!shown.startsWith(name)) continue;
			s.packs.setSelected(e);
			s.choose(e.pack);
			return e.pack.isEmpty() ? "Off" : e.pack;
		}
		return "nothing matches";
	}

	private final class PackList extends ObjectSelectionList<PackEntry> {
		PackList(Minecraft mc, int width, int height, int y) {
			super(mc, width, height, y, 26);
		}

		/** Off, then every installed pack; {@code select} highlighted. */
		void fill(String select) {
			this.clearEntries();
			PackEntry chosen = null;
			for (String pack : ShaderToggle.choices()) {
				PackEntry e = new PackEntry(pack);
				this.addEntry(e);
				if (pack.equals(select)) chosen = e;
			}
			if (this.children().size() == 1) this.addEntry(new PackEntry(null));  // (no packs yet: how to get one)
			if (chosen != null) {
				this.setSelected(chosen);
				this.centerScrollOn(chosen);
			}
		}

		@Override
		public int getRowWidth() {
			return Math.min(360, this.width - 40);
		}
	}

	/** A pack, Off (""), or (null) the note shown when no pack is installed yet. */
	private final class PackEntry extends ObjectSelectionList.Entry<PackEntry> {
		final String pack;
		private final boolean installed;

		PackEntry(String pack) {
			this.pack = pack;
			this.installed = pack == null || pack.isEmpty() || Files.exists(ShaderToggle.packsDirectory().resolve(pack));
		}

		@Override
		public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partial) {
			int x = this.getContentX() + 3, y = this.getContentY() + 2, w = this.getContentWidth() - 6;
			var font = ShaderPacksScreen.this.font;
			if (this.pack == null) {
				g.text(font, font.plainSubstrByWidth("No shader packs yet. Download one (BSL, Complementary...),", w), x, y, 0xFFAAAAAA);
				g.text(font, font.plainSubstrByWidth("then drop its .zip file on this window.", w), x, y + 12, 0xFFAAAAAA);
				return;
			}
			MutableComponent name = Component.literal(this.pack.isEmpty() ? "Off" : ShaderToggle.display(this.pack));
			String active = ShaderSwitcher.active(), target = ShaderSwitcher.target();
			Component state = null;
			if (!CadencePackGate.hooksApplied() && this.pack.equals(ShaderToggle.selectedPack()) && !this.pack.equals(CadencePackGate.selectedPack()))
				state = Component.literal("restart to turn on").withStyle(ChatFormatting.YELLOW);
			else if (this.pack.equals(target) && !this.pack.equals(active)) state = Component.literal("turning on...").withStyle(ChatFormatting.YELLOW);
			else if (this.pack.equals(active) && this.pack.equals(target)) state = Component.literal("in use").withStyle(ChatFormatting.GREEN);
			int stateWidth = state == null ? 0 : font.width(state) + 6;
			String shown = font.plainSubstrByWidth(name.getString(), w - stateWidth);
			g.text(font, Component.literal(shown), x, y, 0xFFFFFFFF);
			if (state != null) g.text(font, state, x + w - font.width(state), y, 0xFFFFFFFF);
			Component detail = this.detail();
			String line = font.plainSubstrByWidth(detail.getString(), w);
			g.text(font, Component.literal(line).withStyle(detail.getStyle()), x, y + 12, 0xFFFFFFFF);
			if (hovered && !line.equals(detail.getString())) g.setTooltipForNextFrame(detail, mouseX, mouseY);
			else if (hovered && !this.pack.isEmpty()) {
				PackCatalog.Known k = PackCatalog.lookup(this.pack);
				if (k != null && k.runs() && ShaderSwitcher.failure(this.pack) == null)
					g.setTooltipForNextFrame(Component.literal(k.weight().label + ": " + k.weight().hint + "."), mouseX, mouseY);
			}
		}

		/** The second line: what Cadence knows about this pack. */
		private Component detail() {
			if (this.pack.isEmpty()) return Component.literal("No shaders: Cadence's fastest renderer").withStyle(ChatFormatting.GRAY);
			if (!this.installed) return Component.literal("Its file is no longer in the shaderpacks folder").withStyle(ChatFormatting.RED);
			String failure = ShaderSwitcher.failure(this.pack);
			if (failure != null) return Component.literal("Couldn't load: " + failure).withStyle(ChatFormatting.RED);
			PackCatalog.Known k = PackCatalog.lookup(this.pack);
			if (k == null) return Component.literal("Not tested with Cadence yet").withStyle(ChatFormatting.GRAY);
			if (!k.runs()) return Component.literal("Doesn't run with Cadence: " + k.note()).withStyle(ChatFormatting.GOLD);
			return Component.literal("Tested with Cadence · " + k.weight().label + (k.note().isEmpty() ? "" : " · " + k.note()))
				.withStyle(ChatFormatting.GRAY);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			if (this.pack == null) return false;
			ShaderPacksScreen.this.packs.setSelected(this);
			ShaderPacksScreen.this.choose(this.pack);
			return true;
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (this.pack == null || !event.isSelection()) return super.keyPressed(event);
			ShaderPacksScreen.this.packs.setSelected(this);
			ShaderPacksScreen.this.choose(this.pack);
			return true;
		}

		@Override
		public Component getNarration() {
			if (this.pack == null) return Component.literal("No shader packs yet. Drop a pack's zip file on this window.");
			return Component.literal(this.pack.isEmpty() ? "Off" : ShaderToggle.display(this.pack));
		}
	}
}
