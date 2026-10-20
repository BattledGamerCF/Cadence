package dev.cadence.shaders.cadence;

import dev.cadence.shaders.cadence.system.HeatControl;
import dev.cadence.shaders.cadence.system.MemoryGuard;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Cadence: the performance part of Video Settings, written for players who don't know what an upscaler is. One preset
 * button sets everything (Battery Saver, Balanced, Max Quality; "Custom" once a setting below is changed by hand); every
 * button says in its tooltip what it does and what it trades; a status line says what Cadence is changing on its own right
 * now (Auto resolution, Heat Control, the memory guard), so nothing changes behind the player's back.
 */
public final class CadenceSettings {
	public enum Preset {
		BATTERY("Battery Saver"), BALANCED("Recommended"), QUALITY("Max Quality"), MAX_FPS("Max FPS"), CUSTOM("Custom");

		final String label;

		Preset(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	private static final List<Runnable> refreshers = new ArrayList<>();

	private CadenceSettings() {
	}

	/** Which preset the current settings match (Custom if none). */
	public static Preset current() {
		boolean auto = RenderScale.isAuto();
		int shadows = ShadowReuse.rate();
		boolean heat = HeatControl.enabled();
		int up = RenderScale.upscaler();
		if (auto && heat && shadows == 2 && up == RenderScale.UPSCALE_APPLE && RenderScale.autoTarget() <= 45) return Preset.BATTERY;
		if (auto && heat && shadows == 2 && up == RenderScale.UPSCALE_APPLE && RenderScale.autoTarget() == 60) return Preset.BALANCED;
		if (!auto && RenderScale.get() >= 0.995f && !heat && shadows == 1) return Preset.QUALITY;
		if (!auto && RenderScale.get() >= 0.995f && !heat && shadows == 2 && up == RenderScale.UPSCALE_FAST && !ShaderToggle.retinaConfigured())
			return Preset.MAX_FPS;
		return Preset.CUSTOM;
	}

	public static void apply(Preset preset) {
		switch (preset) {
			case BATTERY -> {
				RenderScale.setAutoTarget(40);
				RenderScale.setAuto();
				HeatControl.setEnabled(true);
				setShadows(2);
				RenderScale.setUpscaler(RenderScale.UPSCALE_APPLE);
			}
			case BALANCED -> {
				RenderScale.setAutoTarget(60);
				RenderScale.setAuto();
				HeatControl.setEnabled(true);
				setShadows(2);
				RenderScale.setUpscaler(RenderScale.UPSCALE_APPLE);
			}
			case QUALITY -> {
				RenderScale.setAutoTarget(60);
				RenderScale.set(1.0f);
				HeatControl.setEnabled(false);
				setShadows(1);
			}
			// Cadence: as many frames as the Mac can make. Retina off (the game at the screen's point size, a quarter of the pixels,
			// each a crisp 2x2 block; at the next launch): M2 Air, no shaders, 530 -> 1,170 fps. The world at full size of that
			// (with fewer pixels a lower world scale only adds the upscale), the cheapest upscale for a shader pack's own
			// scaling, shadows every 2nd frame, no cooling cap.
			case MAX_FPS -> {
				ShaderToggle.setRetina(false);
				RenderScale.set(1.0f);
				HeatControl.setEnabled(false);
				setShadows(2);
				RenderScale.setUpscaler(RenderScale.UPSCALE_FAST);
			}
			case CUSTOM -> {
			}
		}
	}

	private static void setShadows(int rate) {
		ShadowReuse.setRate(rate);
		ShaderToggle.saveKey("shadowRate", Integer.toString(rate));
	}

	/** After any change: every Cadence button's label follows, and the status rows above them. */
	static void refreshAll() {
		for (Runnable r : refreshers) r.run();
		List<net.minecraft.client.gui.components.AbstractWidget> rows = statusRows;
		if (rows.isEmpty() || statusFont == null) return;
		List<Component> parts = new ArrayList<>();
		for (Component line : status()) parts.addAll(wrap(line, statusWidth, statusFont));
		// as many rows as were built: a longer status joins its tail into the last row (reopening the screen lays it out anew)
		for (int i = 0; i < rows.size(); i++) {
			Component text = i < parts.size() ? parts.get(i) : Component.empty();
			if (i == rows.size() - 1 && parts.size() > rows.size()) {
				net.minecraft.network.chat.MutableComponent joined = parts.get(i).copy();
				for (int j = i + 1; j < parts.size(); j++) joined.append(" ").append(parts.get(j));
				text = joined;
			}
			rows.get(i).setMessage(text);
		}
	}

	private static List<net.minecraft.client.gui.components.AbstractWidget> statusRows = List.of();
	private static int statusWidth;
	private static net.minecraft.client.gui.Font statusFont;

	/** Video Settings, as it builds: the status rows (their widgets), for refreshAll. */
	public static void statusRows(List<net.minecraft.client.gui.components.AbstractWidget> rows, int width, net.minecraft.client.gui.Font font) {
		statusRows = List.copyOf(rows);
		statusWidth = width;
		statusFont = font;
	}

	/** From the render thread's frame code: refresh the labels (a check's progress). */
	public static void refreshLater() {
		net.minecraft.client.Minecraft.getInstance().execute(CadenceSettings::refreshAll);
	}

	/** Video Settings opened: forget the last screen's buttons. */
	public static void newScreen() {
		refreshers.clear();
	}

	static void onRefresh(Runnable r) {
		refreshers.add(r);
	}

	public static Button presetButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(presetLabel(), b -> {
			Preset now = current();
			Preset next = switch (now) {
				case BATTERY -> Preset.BALANCED;
				case BALANCED -> Preset.QUALITY;
				case QUALITY -> Preset.MAX_FPS;
				case MAX_FPS, CUSTOM -> Preset.BATTERY;
			};
			apply(next);
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("One click sets the options below.",
			"Battery Saver|coolest and quietest, aims for 40 fps",
			"Recommended|smooth 60 fps, sharp whenever it can be",
			"Max Quality|always full sharpness; the Mac may run hot",
			"Max FPS|Retina off (crisp 2x2 pixels, about twice the fps; next launch), no limits",
			"Custom|you changed something by hand",
			"#Tip: Max Framerate 60 (120 on ProMotion) saves heat.")).build();
		onRefresh(() -> self[0].setMessage(presetLabel()));
		return self[0];
	}

	private static Component presetLabel() {
		return Component.literal("Performance: " + current().label);
	}

	/** Cooling (HeatControl.Cooling): how early Cadence eases off as the Mac heats up. */
	public static Button heatButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(heatLabel(), b -> {
			HeatControl.Cooling[] all = HeatControl.Cooling.values();
			HeatControl.setCooling(all[(HeatControl.cooling().ordinal() + 1) % all.length]);
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(coolingTip()).build();
		onRefresh(() -> {
			self[0].setMessage(heatLabel());
			self[0].setTooltip(coolingTip());
		});
		return self[0];
	}

	private static Tooltip coolingTip() {
		int fans = dev.cadence.metal.MacSystem.fanCount();
		double watts = dev.cadence.metal.MacSystem.systemWatts();
		int rpm = dev.cadence.metal.MacSystem.fanRpm();
		String now = "#Right now: " + HeatControl.thermalWords()
			+ (watts > 0 ? String.format(java.util.Locale.ROOT, ", %.0f W", watts) : "")
			+ (fans > 0 ? (rpm >= 0 ? ", fans at " + rpm + " rpm" : ", " + fans + " fan" + (fans > 1 ? "s" : "")) : fans == 0 ? ", no fans" : "");
		java.util.List<String> lines = new java.util.ArrayList<>(java.util.List.of(
			"Quiet|when warm, holds your screen's refresh rate",
			"Balanced|when warm, holds twice your screen's rate (recommended)",
			"Max Performance|full speed until macOS slows the Mac" + (fans > 0 ? "; fans work harder" : ""),
			"Off|never eases off"));
		if (fans > 0) lines.add("#Fan speed itself needs administrator rights, so Cadence doesn't set it.");
		lines.add(now);
		return Tips.of("Keeps your Mac's temperature steady, so the game stays smooth.", lines.toArray(String[]::new));
	}

	private static Component heatLabel() {
		if (HeatControl.level() > 0) return Component.literal("Cooling: Easing Off").withStyle(ChatFormatting.YELLOW);
		return Component.literal("Cooling: " + HeatControl.cooling().label);
	}

	/** Leaves (SmartLeaves): Detailed (vanilla) or Smart. */
	public static Button leavesButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(leavesLabel(), b -> {
			dev.cadence.shaders.cadence.mesh.SmartLeaves.setEnabled(!dev.cadence.shaders.cadence.mesh.SmartLeaves.enabled());
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("With See-Through Leaves on:",
			"Smart|leaves buried inside a canopy aren't drawn; forests up to 2x faster",
			"Detailed|every leaf face, as in vanilla")).build();
		onRefresh(() -> self[0].setMessage(leavesLabel()));
		return self[0];
	}

	private static Component leavesLabel() {
		return Component.literal("Leaves: " + (dev.cadence.shaders.cadence.mesh.SmartLeaves.enabled() ? "Smart" : "Detailed"));
	}

	// ---- FPS Counter (the Cadence HUD's "NN fps", read by it through cadence.fpsCounter) and Benchmark Details ----
	static {
		System.setProperty("cadence.fpsCounter", ShaderToggle.configValue("fpsCounter", "true").strip());
	}
	private static volatile boolean benchmarkDetailed = "detailed".equals(ShaderToggle.configValue("benchmarkDetails", "simple").strip());

	/** Whether the Benchmark card shows the technical figures (1% low, worst frame, GPU and CPU time). */
	public static boolean benchmarkDetailed() {
		return benchmarkDetailed;
	}

	public static Button fpsCounterButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(fpsCounterLabel(), b -> {
			boolean on = !"true".equals(System.getProperty("cadence.fpsCounter"));
			System.setProperty("cadence.fpsCounter", Boolean.toString(on));
			ShaderToggle.saveKey("fpsCounter", Boolean.toString(on));
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("The frame rate in the top-left corner while you play.")).build();
		onRefresh(() -> self[0].setMessage(fpsCounterLabel()));
		return self[0];
	}

	private static Component fpsCounterLabel() {
		return Component.literal("FPS Counter: " + ("true".equals(System.getProperty("cadence.fpsCounter")) ? "On" : "Off"));
	}

	public static Button benchmarkDetailsButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(benchmarkDetailsLabel(), b -> {
			benchmarkDetailed = !benchmarkDetailed;
			ShaderToggle.saveKey("benchmarkDetails", benchmarkDetailed ? "detailed" : "simple");
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("What the Benchmark's results show.",
			"Simple|the average frame rate and what it ran on",
			"Detailed|also the slowest 1% of frames, the worst single frame, and graphics and processor time per frame")).build();
		onRefresh(() -> self[0].setMessage(benchmarkDetailsLabel()));
		return self[0];
	}

	private static Component benchmarkDetailsLabel() {
		return Component.literal("Benchmark Details: " + (benchmarkDetailed ? "Detailed" : "Simple"));
	}

	/** Render Max FPS (MaxFpsMode): the world not drawn at all, for the highest frame rate possible. Not saved. */
	public static Button maxFpsButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(maxFpsLabel(), b -> {
			MaxFpsMode.set(!MaxFpsMode.on());
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("For curiosity: the most frames a second Minecraft can draw on this Mac.",
			"#It removes all block faces and everything else in the world from the picture (the sky, clouds, mobs, particles, your hand): you see a black void with your HUD. The game keeps running underneath. For the highest number, set Max Framerate to Unlimited and turn VSync off. It turns itself off when Minecraft restarts.")).build();
		onRefresh(() -> self[0].setMessage(maxFpsLabel()));
		return self[0];
	}

	private static Component maxFpsLabel() {
		return Component.literal("Render Max FPS: " + (MaxFpsMode.on() ? "On" : "Off"));
	}

	/** On Battery (system.BatteryMode): Match Display, Saver, Off. */
	public static Button batteryButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(batteryLabel(), b -> {
			dev.cadence.shaders.cadence.system.BatteryMode.cycle();
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("How fast Minecraft draws while your Mac runs on its battery.",
			"Match Display|at most your display's refresh rate (60, or 120 on ProMotion): nothing you can see is lost",
			"Saver|at most 30 frames a second, for long sessions away from a charger",
			"Off|as when plugged in",
			"#Plugged in, nothing is capped. Unplugged, a Mac drawing hundreds of frames a second shows only its display's share and spends the rest of the battery as heat.")).build();
		onRefresh(() -> self[0].setMessage(batteryLabel()));
		return self[0];
	}

	private static Component batteryLabel() {
		return Component.literal("On Battery: " + dev.cadence.shaders.cadence.system.BatteryMode.mode().label);
	}

	/** Dynamic Lights (DynamicLights): Off, Held Items, All. */
	public static Button dynamicLightsButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(dynamicLightsLabel(), b -> {
			DynamicLights.cycle();
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Light from torches and other light sources that move, as a placed one would give.",
			"Held Items|a torch, lantern or glowstone in anyone's hand lights the world around them",
			"All|also dropped light items, burning mobs, blazes, fireballs, lit TNT, glowing items in frames",
			"Off|as vanilla",
			"#Lit on the GPU, nothing rebuilt: under 0.1 ms a frame even with 16 lights. With a shader pack on, the pack's own held-item light applies instead.")).build();
		onRefresh(() -> self[0].setMessage(dynamicLightsLabel()));
		if (DynamicLights.LAMB) {
			self[0].active = false;
			self[0].setTooltip(Tips.of("LambDynamicLights is installed and lights moving light sources itself; Cadence's own stay off."));
		} else if (!dev.cadence.metal.own.OwnTerrain.FLAG) {
			self[0].setTooltip(Tips.of("Light from torches and other light sources that move.",
				"#They need Cadence's terrain renderer, which is off this session (the game started with a shader pack on, or another "
					+ "mod needs vanilla's). Start the game with shaders off to use them."));
		}
		return self[0];
	}

	private static Component dynamicLightsLabel() {
		return Component.literal("Dynamic Lights: " + (DynamicLights.LAMB ? "LambDynamicLights" : DynamicLights.mode().label));
	}

	/** Cadence Messages (CadenceMessages): the join line and the corner notices. */
	public static Button messagesButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(messagesLabel(), b -> {
			dev.cadence.shaders.cadence.system.CadenceMessages.setEnabled(!dev.cadence.shaders.cadence.system.CadenceMessages.enabled());
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Cadence's chat line and corner pop-ups.",
			"On|a line when you join, a pop-up when Cadence adjusts something",
			"Off|quiet; what Cadence is doing still shows here")).build();
		onRefresh(() -> self[0].setMessage(messagesLabel()));
		return self[0];
	}

	private static Component messagesLabel() {
		return Component.literal("Cadence Messages: " + (dev.cadence.shaders.cadence.system.CadenceMessages.enabled() ? "On" : "Off"));
	}

	/** In Background (BackgroundFps): frames drawn while another app is active. */
	public static Button backgroundButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(backgroundLabel(), b -> {
			dev.cadence.shaders.cadence.system.BackgroundFps.setEnabled(!dev.cadence.shaders.cadence.system.BackgroundFps.enabled());
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("While another app is in front:",
			dev.cadence.shaders.cadence.system.BackgroundFps.FPS + " fps|the game keeps running, drawn slowly; saves battery and heat",
			"Normal|full Max Framerate, as in vanilla")).build();
		onRefresh(() -> self[0].setMessage(backgroundLabel()));
		return self[0];
	}

	private static Component backgroundLabel() {
		return Component.literal("In Background: " + (dev.cadence.shaders.cadence.system.BackgroundFps.enabled()
			? dev.cadence.shaders.cadence.system.BackgroundFps.FPS + " fps" : "Normal"));
	}

	/** Faster Shadows (PackCheck): the label says what is known about the pack in use; a click tests it when that's unknown. */
	public static Button packCheckButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(packCheckLabel(), b -> {
			if (dev.cadence.shaders.cadence.packcheck.PackCheck.running()) return;
			if (dev.cadence.shaders.runtime.ShaderRuntime.get() == null) return;
			dev.cadence.shaders.cadence.packcheck.PackCheck.start(CadenceSettings::refreshAll);
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Shaders only: Cadence's faster shadow drawing (often 10-25% more fps).",
			"On|this pack uses it",
			"Test|click to check whether this pack can: the picture is compared with it on and off, and it's kept only if identical"
				+ " (about 10 seconds, game paused)",
			"Not Safe|this pack's picture changes with it, so it stays off",
			"#Needs a shader pack on, in a world.")).build();
		onRefresh(() -> self[0].setMessage(packCheckLabel()));
		return self[0];
	}

	private static Component packCheckLabel() {
		if (dev.cadence.shaders.cadence.packcheck.PackCheck.running())
			return Component.literal("Faster Shadows: Testing").withStyle(ChatFormatting.YELLOW);
		var runtime = dev.cadence.shaders.runtime.ShaderRuntime.get();
		if (runtime == null || runtime.programs() == null)
			return Component.literal(net.minecraft.client.Minecraft.getInstance().level == null ? "Faster Shadows: No World" : "Faster Shadows: No Pack")
				.withStyle(ChatFormatting.GRAY);
		if (runtime.programs().shadowCullingEligibility.eligible()) return Component.literal("Faster Shadows: On");
		var saved = dev.cadence.shaders.cadence.packcheck.PackCheck.savedResult();
		if (saved == null) return Component.literal("Faster Shadows: Test");
		return switch (saved) {
			case SAFE -> Component.literal("Faster Shadows: On").withStyle(ChatFormatting.GREEN);
			case UNSAFE -> Component.literal("Faster Shadows: Not Safe").withStyle(ChatFormatting.GRAY);
			case INCONCLUSIVE -> Component.literal("Faster Shadows: Unsure").withStyle(ChatFormatting.GRAY);
		};
	}

	/** line split into pieces no wider than width (words kept whole; its style kept), for list headers that would clip. */
	public static List<Component> wrap(Component line, int width, net.minecraft.client.gui.Font font) {
		List<Component> out = new ArrayList<>();
		String text = line.getString();
		if (font.width(text) <= width) {
			out.add(line);
			return out;
		}
		// break at " · " between items first (keeps "Cooling Balanced" together), then at spaces inside a long item
		String[] units = text.contains(" · ") ? text.split(" · ") : text.split(" ");
		String sep = text.contains(" · ") ? " · " : " ";
		List<String> pieces = new ArrayList<>();
		for (String u : units) {
			if (font.width(u) <= width || !sep.equals(" · ")) pieces.add(u);
			else pieces.addAll(java.util.Arrays.asList(u.split(" ")));
		}
		StringBuilder cur = new StringBuilder();
		for (String word : pieces) {
			String next = cur.length() == 0 ? word : cur + sep + word;
			if (cur.length() > 0 && font.width(next) > width) {
				out.add(Component.literal(cur.toString()).withStyle(line.getStyle()));
				cur = new StringBuilder(word);
			} else cur = new StringBuilder(next);
		}
		if (cur.length() > 0) out.add(Component.literal(cur.toString()).withStyle(line.getStyle()));
		return out;
	}

	/** What Cadence is changing on its own right now, one short line each (a gray "nothing" line when nothing). */
	public static List<Component> status() {
		List<Component> lines = new ArrayList<>();
		// always one short line: Cadence tunes the game on its own by default, and says so (each button shows its own setting)
		List<String> auto = new ArrayList<>();
		if (RenderScale.isAuto()) auto.add("resolution");
		if (HeatControl.enabled()) auto.add("cooling");
		if (dev.cadence.shaders.cadence.system.BackgroundFps.enabled()) auto.add("background fps");
		lines.add(auto.isEmpty() ? Component.literal("Automatic tuning off: your settings as set").withStyle(ChatFormatting.GRAY)
			: Component.literal("Tuned automatically: " + String.join(" · ", auto)).withStyle(ChatFormatting.GREEN));
		if (HeatControl.level() > 0) lines.add(Component.literal(HeatControl.shortDescribe()).withStyle(ChatFormatting.YELLOW));
		if (MemoryGuard.level() > 0) lines.add(Component.literal(MemoryGuard.shortDescribe()).withStyle(ChatFormatting.YELLOW));
		// other mods that hook what Cadence replaces: those areas run vanilla's code for them (CompatLayers; the log has the detail)
		List<String> mods = dev.cadence.metal.compat.CompatLayers.mods();
		if (!mods.isEmpty()) lines.add(Component.literal("Works alongside: " + String.join(" · ", mods)).withStyle(ChatFormatting.AQUA));
		return lines;
	}
}
