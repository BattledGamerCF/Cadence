package dev.cadence.shaders.cadence.benchmark;

import dev.cadence.shaders.cadence.ui.Tips;
import dev.cadence.shaders.cadence.setup.CadenceConfirmScreen;
import dev.cadence.shaders.cadence.system.Notices;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Cadence Benchmark's screens, in the same style as Quick Setup's: the Video Settings button, a short confirm screen, and the
 * results card (plain words, one number per line, what it was measured on, and the last run for comparison).
 */
public final class BenchmarkScreens {
	private BenchmarkScreens() {
	}

	/** The Video Settings button: greyed out, with the reason as its tooltip, when no world is open. */
	public static Button button(Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		Button b = Button.builder(Component.literal("Benchmark..."), x -> mc.gui.setScreen(offer(parent)))
			.width(Button.DEFAULT_WIDTH)
			.tooltip(Tips.of(Tips.Impact.NONE, "Measures how smooth the game is on your Mac (about 30 seconds).",
				"#Your view turns once round in place. Compare two settings, or two Macs.",
				"#Needs a world. Results stay on this Mac."))
			.build();
		if (Benchmark.cannotStart() != null) {
			b.active = false;
			b.setTooltip(Tips.of("Open a world first.", "#The benchmark turns your view once round, in a world."));
		}
		return b;
	}

	/** What the run does, before it starts. */
	public static Screen offer(Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		return new CadenceConfirmScreen(yes -> {
			if (yes) run(parent);
			else mc.gui.setScreen(parent);
		}, Component.literal("Benchmark your Mac"),
			Component.literal("This measures how smooth Minecraft runs with your current settings. It takes about 30 seconds: your view "
				+ "turns slowly once all the way round, with the screen clear of menus and the HUD hidden.\nDon't touch anything while it "
				+ "runs. Press Escape to stop.\nThe results stay on this Mac. You can save them as a picture to share."),
			Component.literal("Start"), Component.literal("Cancel"));
	}

	/** Starts the run now; the results card (or a short notice, if it stopped early) follows. */
	public static void run(Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		Benchmark.start(r -> {
			double[] previous = Benchmark.previous();
			if (!dev.cadence.shaders.cadence.render.MaxFpsMode.on()) Benchmark.remember(r);  // (a void isn't a baseline)
			mc.gui.setScreen(new ResultsScreen(r, previous, parent));
		}, reason -> {
			// (not over a screen that stopped it: the pause menu, a death screen)
			if (mc.level != null && mc.gui.screen() == null) mc.gui.setScreen(parent);
			Notices.post("Benchmark stopped", reason);
		});
	}

	/** Self-test: Save Image pressed on the open results card. */
	public static void pressSave(Screen screen) {
		if (screen instanceof ResultsScreen results) results.beginCapture();
	}

	/** The results card. */
	static final class ResultsScreen extends Screen {
		private static final int CARD_W = 340, ROW = 12;
		private final Benchmark.Result r;
		private final double[] previous;
		private final Screen parent;
		private final List<Button> buttons = new ArrayList<>();
		private volatile String saved = "";
		/** 0 normal; 1 buttons hidden, waiting to be drawn once; 2 that frame is on screen: take the picture. */
		private int capture;

		ResultsScreen(Benchmark.Result r, double[] previous, Screen parent) {
			super(Component.literal("Cadence Benchmark"));
			this.r = r;
			this.previous = previous;
			this.parent = parent;
		}

		@Override
		protected void init() {
			this.buttons.clear();
			// (four buttons in a row; narrower on a small window or a big GUI scale)
			int gap = 6, w = Math.max(60, Math.min(100, (this.width - 12 - 3 * gap) / 4)), x = this.width / 2 - (4 * w + 3 * gap) / 2,
				y = Math.min(this.height - 28, this.cardTop() + this.cardHeight() + 26);
			this.buttons.add(this.addRenderableWidget(Button.builder(Component.literal("Save Image"), b -> this.beginCapture())
				.bounds(x, y, w, 20).tooltip(Tips.of("Saves this card as a picture in your screenshots folder.")).build()));
			boolean detailed = dev.cadence.shaders.cadence.ui.CadenceSettings.benchmarkDetailed();
			this.buttons.add(this.addRenderableWidget(Button.builder(Component.literal(detailed ? "Fewer Details" : "More Details"), b -> {
					dev.cadence.shaders.cadence.ui.CadenceSettings.setBenchmarkDetailed(!dev.cadence.shaders.cadence.ui.CadenceSettings.benchmarkDetailed());
					this.rebuildWidgets();
				})
				.bounds(x + w + gap, y, w, 20).tooltip(Tips.of(detailed ? "Back to the average frame rate and what it ran on."
					: "Also the slowest 1% of frames, the worst single frame, and graphics and processor time per frame.",
					"#Remembered for the next benchmark.")).build()));
			this.buttons.add(this.addRenderableWidget(Button.builder(Component.literal("Run Again"), b -> run(this.parent))
				.bounds(x + 2 * (w + gap), y, w, 20).build()));
			this.buttons.add(this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
				.bounds(x + 3 * (w + gap), y, w, 20).build()));
		}

		@Override
		public void onClose() {
			this.minecraft.gui.setScreen(this.parent);
		}

		/** The card's rows: label, value, and an optional colour (0 = white). A null label is a gap. */
		private List<String[]> rows() {
			List<String[]> rows = new ArrayList<>();
			rows.add(new String[] {"Average speed", fps(r.avgFps()), "g"});
			// (how steady, beside how fast: the slowest 1% of frames, in the simple view too)
			rows.add(new String[] {"Slowest frames (1% low)", fps(r.lowFps()), ""});
			boolean detailed = dev.cadence.shaders.cadence.ui.CadenceSettings.benchmarkDetailed();
			if (detailed) {  // (Benchmark Details: Detailed; Simple, the default, keeps the card to what anyone can read)
				rows.add(new String[] {"Worst single frame", String.format(Locale.ROOT, "%.1f ms", r.worstMs()), ""});
				rows.add(new String[] {"Graphics time per frame", r.gpuMs() < 0 ? "not available" : String.format(Locale.ROOT, "%.1f ms", r.gpuMs()), ""});
				rows.add(new String[] {"Processor time per frame", r.cpuMs() < 0 ? "not available" : String.format(Locale.ROOT, "%.1f ms", r.cpuMs()), ""});
			}
			if (previous != null) rows.add(new String[] {"Last time", detailed
				? String.format(Locale.ROOT, "%.0f fps (slowest 1%%: %.0f)", previous[0], previous[1]) : String.format(Locale.ROOT, "%.0f fps", previous[0]),
				r.avgFps() >= previous[0] * 1.03 ? "g" : r.avgFps() <= previous[0] * 0.97 ? "r" : "y"});
			rows.add(null);
			for (String line : r.setup()) {
				int bar = line.indexOf('|');
				rows.add(new String[] {line.substring(0, bar), line.substring(bar + 1), "d"});
			}
			return rows;
		}

		private static String fps(double v) {
			return String.format(Locale.ROOT, "%.0f fps", v);
		}

		private int cardHeight() {
			return 34 + this.rows().stream().mapToInt(row -> row == null ? 8 : ROW).sum() + 8;
		}

		private int cardTop() {
			return Math.max(6, (this.height - this.cardHeight() - 50) / 2);
		}

		@Override
		public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
			super.extractBackground(graphics, mouseX, mouseY, partial);
			if (this.minecraft != null && this.minecraft.level != null) graphics.fill(0, 0, this.width, this.height, 0xB0101014);
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
			if (this.capture == 2) {
				// the previous frame was this card without its buttons: that is the picture
				this.capture = 0;
				this.takePicture();
				for (Button b : this.buttons) b.visible = true;
			}
			super.extractRenderState(g, mouseX, mouseY, partial);
			int w = Math.min(CARD_W, this.width - 12), left = (this.width - w) / 2, top = this.cardTop(), right = left + w;
			g.fill(left - 1, top - 1, right + 1, top + this.cardHeight() + 1, 0xFF4A4A5A);
			g.fill(left, top, right, top + this.cardHeight(), 0xF0181820);
			g.centeredText(this.font, "Cadence Benchmark", this.width / 2, top + 8, 0xFFFFFFFF);
			g.centeredText(this.font, "30 seconds, the view turning once round", this.width / 2, top + 20, 0xFF9A9AA8);
			int y = top + 34;
			for (String[] row : this.rows()) {
				if (row == null) {
					g.fill(left + 10, y + 3, right - 10, y + 4, 0xFF3A3A48);
					y += 8;
					continue;
				}
				int color = switch (row[2]) {
					case "g" -> 0xFF7CE08A;
					case "r" -> 0xFFE88080;
					case "y" -> 0xFFE8D880;
					case "d" -> 0xFFD0D0DA;
					default -> 0xFFFFFFFF;
				};
				g.text(this.font, row[0], left + 10, y, 0xFF9A9AA8);
				String value = row[1];
				int room = w - 20 - this.font.width(row[0]) - 10;
				if (this.font.width(value) > room) value = this.font.plainSubstrByWidth(value, room - this.font.width("...")) + "...";
				g.text(this.font, value, right - 10 - this.font.width(value), y, color);
				y += ROW;
			}
			if (this.capture == 0 && !this.saved.isEmpty())
				g.centeredText(this.font, this.saved, this.width / 2, top + this.cardHeight() + 8, this.saved.startsWith("Couldn") ? 0xFFE88080 : 0xFF7CE08A);
			if (this.capture == 1) this.capture = 2;
		}

		/** Save Image: the buttons are hidden for one drawn frame, then that frame is saved. */
		private void beginCapture() {
			this.saved = "";
			this.capture = 1;
			for (Button b : this.buttons) b.visible = false;
		}

		private void takePicture() {
			Minecraft mc = this.minecraft;
			String name = Benchmark.fileStem(this.r) + ".png";
			Path file = mc.gameDirectory.toPath().resolve("screenshots").resolve(name);
			try {
				this.saved = "Saving...";
				Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, message -> this.saved = Files.exists(file)
					? "Saved: screenshots/" + name : "Couldn't save the picture.");
			} catch (RuntimeException e) {
				this.saved = "Couldn't save the picture.";
			}
		}
	}
}
