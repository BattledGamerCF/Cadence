package dev.cadence.shaders.cadence.ui;

import dev.cadence.shaders.cadence.render.DynamicLights;
import dev.cadence.shaders.cadence.render.MaxFpsMode;
import dev.cadence.shaders.cadence.render.RenderScale;
import dev.cadence.shaders.cadence.shadow.ShadowReuse;

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
		// (only Max FPS turns Retina off: the others want every pixel, so a Retina left off by hand makes them Custom)
		if (!ShaderToggle.retinaConfigured() && !(shadows == 2 && !auto && !heat)) return Preset.CUSTOM;
		if (auto && heat && shadows == 2 && up == RenderScale.UPSCALE_APPLE && RenderScale.autoTarget() <= 45) return Preset.BATTERY;
		if (auto && heat && shadows == 2 && up == RenderScale.UPSCALE_APPLE && RenderScale.autoTarget() == 60) return Preset.BALANCED;
		if (!auto && RenderScale.get() >= 0.995f && !heat && shadows == 1) return Preset.QUALITY;
		if (!auto && RenderScale.get() >= 0.995f && !heat && shadows == 2 && up == RenderScale.UPSCALE_FAST && !ShaderToggle.retinaConfigured())
			return Preset.MAX_FPS;
		return Preset.CUSTOM;
	}

	public static void apply(Preset preset) {
		if (preset == Preset.MAX_FPS) maxFpsLooks();
		else if (preset != Preset.CUSTOM) restoreLooks();
		switch (preset) {
			case BATTERY -> {
				dev.cadence.shaders.cadence.render.FrameGenSetting.set(dev.cadence.metal.framegen.FrameGen.Mode.SMOOTH);
				ShaderToggle.setRetina(true);  // (back on after Max FPS; at the next launch, as the button says)
				RenderScale.setAutoTarget(40);
				RenderScale.setAuto();
				HeatControl.setEnabled(true);
				setShadows(2);
				RenderScale.setUpscaler(RenderScale.UPSCALE_APPLE);
			}
			case BALANCED -> {
				dev.cadence.shaders.cadence.render.FrameGenSetting.set(dev.cadence.metal.framegen.FrameGen.Mode.SMOOTH);
				ShaderToggle.setRetina(true);  // (back on after Max FPS; at the next launch, as the button says)
				RenderScale.setAutoTarget(60);
				RenderScale.setAuto();
				HeatControl.setEnabled(true);
				setShadows(2);
				RenderScale.setUpscaler(RenderScale.UPSCALE_APPLE);
			}
			case QUALITY -> {
				dev.cadence.shaders.cadence.render.FrameGenSetting.set(dev.cadence.metal.framegen.FrameGen.Mode.SMOOTH);
				ShaderToggle.setRetina(true);  // (back on after Max FPS; at the next launch, as the button says)
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
				dev.cadence.shaders.cadence.render.FrameGenSetting.set(dev.cadence.metal.framegen.FrameGen.Mode.OFF);
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

	/**
	 * Max FPS: leaves drawn solid (vanilla's Leaves: Fast) and no clouds, the player's own choices kept in the config to come
	 * back with any other preset. Measured on an M2 Air, fullscreen with Retina off, render distance 6, cooled runs: 1,781 fps
	 * as the player had them, 2,095 with these (alpha-tested leaves turn off the GPU's hidden-surface removal; the clouds were 5%).
	 */
	private static void maxFpsLooks() {
		var o = net.minecraft.client.Minecraft.getInstance().options;
		if (ShaderToggle.configValue("maxFps.leaves", "").isEmpty()) {
			ShaderToggle.saveKey("maxFps.leaves", Boolean.toString(o.cutoutLeaves().get()));
			ShaderToggle.saveKey("maxFps.clouds", o.cloudStatus().get().name());
			ShaderToggle.saveKey("maxFps.entityDistance", Double.toString(o.entityDistanceScaling().get()));
		}
		boolean rebuild = o.cutoutLeaves().get();
		o.cutoutLeaves().set(false);
		o.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
		o.entityDistanceScaling().set(Math.min(o.entityDistanceScaling().get(), MAX_FPS_ENTITY_DISTANCE));
		o.save();
		farPlants();
		if (rebuild && net.minecraft.client.Minecraft.getInstance().level != null) net.minecraft.client.Minecraft.getInstance().levelExtractor.allChanged();
	}

	/**
	 * Max FPS's entity distance (vanilla's Entity Distance option, 75%) and Far Plants (OwnTerrain.farPlantBlocks: grass, flowers
	 * and ferns past 96 blocks not drawn; glass and every other cutout face still are). M2 Air, fullscreen, render and simulation
	 * distance 16, in-run A/B: entity distance 100% -> 75% GPU -0.10 ms, render thread -0.06 ms; far plants GPU -0.04 ms, no
	 * difference in the screenshots (a plant at 96 blocks is a few pixels, mostly behind the terrain in front of it).
	 */
	private static final double MAX_FPS_ENTITY_DISTANCE = 0.75;
	private static final float FAR_PLANTS_BLOCKS = 96;

	/** Far Plants on while Max FPS's looks are (the saved leaves mark them); at launch (ShaderLoaderMod) and on every preset change. */
	public static void farPlants() {
		boolean on = !ShaderToggle.configValue("maxFps.leaves", "").strip().isEmpty();
		try {
			dev.cadence.metal.own.OwnTerrain.farPlantBlocks = on && System.getProperty("cadence.own.farPlants") == null ? FAR_PLANTS_BLOCKS
				: Float.parseFloat(System.getProperty("cadence.own.farPlants", "0"));
		} catch (NumberFormatException | LinkageError e) {
			// (a bad -D value or no Metal module: plants as usual)
		}
	}

	/** Leaving Max FPS for another preset: the player's leaves, clouds and entity distance as they were before it. */
	private static void restoreLooks() {
		String leaves = ShaderToggle.configValue("maxFps.leaves", "").strip(), clouds = ShaderToggle.configValue("maxFps.clouds", "").strip();
		if (leaves.isEmpty()) return;
		var o = net.minecraft.client.Minecraft.getInstance().options;
		boolean cut = Boolean.parseBoolean(leaves), rebuild = o.cutoutLeaves().get() != cut;
		o.cutoutLeaves().set(cut);
		try {
			o.cloudStatus().set(net.minecraft.client.CloudStatus.valueOf(clouds));
		} catch (IllegalArgumentException e) {
			o.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY);
		}
		String entities = ShaderToggle.configValue("maxFps.entityDistance", "").strip();
		if (!entities.isEmpty()) {
			try {
				o.entityDistanceScaling().set(Double.parseDouble(entities));
			} catch (NumberFormatException e) {
				o.entityDistanceScaling().set(1.0);
			}
		}
		o.save();
		ShaderToggle.saveKey("maxFps.leaves", "");
		ShaderToggle.saveKey("maxFps.clouds", "");
		ShaderToggle.saveKey("maxFps.entityDistance", "");
		farPlants();
		if (rebuild && net.minecraft.client.Minecraft.getInstance().level != null) net.minecraft.client.Minecraft.getInstance().levelExtractor.allChanged();
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
		// a status with more or fewer lines than rows built (a restart note, Cooling easing off): the screen lays itself out
		// again on the next tick (not inside the click that changed it), keeping its scroll position
		if (parts.size() != rows.size() && relayout != null) {
			Runnable again = relayout;
			relayout = null;  // (once: the new layout registers its own)
			net.minecraft.client.Minecraft.getInstance().execute(again);
			return;
		}
		for (int i = 0; i < rows.size(); i++) rows.get(i).setMessage(i < parts.size() ? parts.get(i) : Component.empty());
	}

	private static Runnable relayout;

	private static List<net.minecraft.client.gui.components.AbstractWidget> statusRows = List.of();
	private static int statusWidth;
	private static net.minecraft.client.gui.Font statusFont;

	/** Video Settings, as it builds: the status rows (their widgets), for refreshAll. */
	public static void statusRows(List<net.minecraft.client.gui.components.AbstractWidget> rows, int width, net.minecraft.client.gui.Font font,
		Runnable relayoutScreen) {
		statusRows = List.copyOf(rows);
		relayout = relayoutScreen;
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
			"Max FPS|Retina off (crisp 2x2 pixels, about twice the fps; next launch), fast leaves, no clouds, no limits: 2,000+ fps fullscreen on an M2 Air",
			"Custom|you changed something by hand",
			"#Vanilla's Preset further down is separate: it sets Minecraft's own quality options.")).build();
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
			boolean on = "false".equals(System.getProperty("cadence.fpsCounter"));  // (anything but "false" shows it, as the HUD reads it)
			System.setProperty("cadence.fpsCounter", Boolean.toString(on));
			ShaderToggle.saveKey("fpsCounter", Boolean.toString(on));
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("The frame rate in the top-left corner while you play.")).build();
		onRefresh(() -> self[0].setMessage(fpsCounterLabel()));
		return self[0];
	}

	public static Button replayButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(replayLabel(), b -> {
			boolean on = !dev.cadence.metal.rec.Replay.wanted();
			dev.cadence.metal.rec.Replay.setEnabled(on);
			ShaderToggle.saveKey("instantReplay", Boolean.toString(on));
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Keeps the last 30 seconds of play; Save Replay (F10) saves them as a video in the replays folder.",
			"#Recorded by your Mac's video encoder at 1280 wide (about 30 MB of memory, a small GPU cost each frame); no sound.")).build();
		onRefresh(() -> self[0].setMessage(replayLabel()));
		return self[0];
	}

	private static Component replayLabel() {
		return Component.literal("Instant Replay: " + (dev.cadence.metal.rec.Replay.enabled() ? "On (F10 saves)" : "Off"));
	}

	public static Button overlayButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(overlayLabel(), b -> {
			dev.cadence.shaders.cadence.render.PerformanceOverlay.toggle();
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("A few lines under the fps counter: frames shown and rendered, frame and GPU time, the Mac's heat and power, battery time left.")).build();
		onRefresh(() -> self[0].setMessage(overlayLabel()));
		return self[0];
	}

	private static Component overlayLabel() {
		return Component.literal("Performance Overlay: " + (dev.cadence.shaders.cadence.render.PerformanceOverlay.on() ? "On" : "Off"));
	}

	private static Component fpsCounterLabel() {
		return Component.literal("FPS Counter: " + ("false".equals(System.getProperty("cadence.fpsCounter")) ? "Off" : "On"));
	}

	/** The Benchmark card's More/Fewer Details button; saved as benchmarkDetails=. */
	public static void setBenchmarkDetailed(boolean on) {
		benchmarkDetailed = on;
		ShaderToggle.saveKey("benchmarkDetails", on ? "detailed" : "simple");
	}

	/** Render Max FPS (MaxFpsMode): the game at the highest frame rate it can render while playable. Not saved. */
	public static Button maxFpsButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(maxFpsLabel(), b -> {
			MaxFpsMode.set(!MaxFpsMode.chosen());
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("The most frames a second this Mac can render Minecraft, still playable.",
			"#Everything is set for you: each block drawn in one colour (a 1x1 texture), lit and shaded as Minecraft's flat lighting does, entities as boxes, the hotbar, health, food and an F3 counter, at a low resolution with no upscaling, frame limit or VSync. Frame interpolation doubles the frames shown; the counter shows both. Any open screen draws the game as usual. Off again after a restart.")).build();
		onRefresh(() -> self[0].setMessage(maxFpsLabel()));
		return self[0];
	}

	private static Component maxFpsLabel() {
		return Component.literal("Render Max FPS: " + (MaxFpsMode.chosen() ? "On" : "Off"));
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
			"#Plugged in, nothing is capped.")).build();
		onRefresh(() -> self[0].setMessage(batteryLabel()));
		return self[0];
	}

	private static Component batteryLabel() {
		return Component.literal("On Battery: " + dev.cadence.shaders.cadence.system.BatteryMode.mode().label);
	}

	/** Dynamic Lights (DynamicLights): Off, Held Items, All. */
	/** Frame Generation (FrameGenSetting, FrameGen): Smooth, Responsive, Off. */
	public static Button frameGenButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(frameGenLabel(), b -> {
			dev.cadence.shaders.cadence.render.FrameGenSetting.cycle();
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Twice the frames on screen for the frames your Mac renders: a frame made between each two.",
			"Smooth|the frame halfway between the last two, shown before the newest (one frame later on screen)",
			"Responsive|the newest frame turned to where your mouse looks now: no added delay, a little blur at moving edges",
			"Off|every frame rendered",
			"#Only where it adds frames your screen can show: Battery Saver renders 30 and shows 60; Recommended and Max Quality render "
				+ "60 and show 120 on a 120 Hz screen. Not with Max FPS. The fps counter shows both (\"120 fps (60 rendered)\").")).build();
		onRefresh(() -> self[0].setMessage(frameGenLabel()));
		return self[0];
	}

	private static Component frameGenLabel() {
		var mode = dev.cadence.metal.framegen.FrameGen.mode();
		return Component.literal("Frame Generation: " + mode.label + (mode != dev.cadence.metal.framegen.FrameGen.Mode.OFF && dev.cadence.metal.framegen.FrameGen.running()
			? " (" + dev.cadence.metal.framegen.FrameGen.renderedRate() + " -> " + 2 * dev.cadence.metal.framegen.FrameGen.renderedRate() + ")" : ""));
	}

	/** Render Distance: Auto (AutoRenderDistance). */
	public static Button autoRenderDistanceButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(autoRdLabel(), b -> {
			dev.cadence.shaders.cadence.render.AutoRenderDistance.toggle();
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Lowers the render distance when your Mac can't hold its frame rate, and raises it again when it can.",
			"#With a shader pack and Auto resolution, once the resolution is as low as Auto takes it: 2 chunks at a time, not below 8 and never past the distance you chose; "
				+ "changes are rare (each makes the game build the nearby chunks again). Far terrain still reaches past it. Off: your distance always.")).build();
		onRefresh(() -> self[0].setMessage(autoRdLabel()));
		return self[0];
	}

	/** Keep Server Chunks (ServerChunks). */
	public static Button keepServerChunksButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(keepChunksLabel(), b -> {
			dev.cadence.shaders.cadence.server.ServerChunks.toggle();
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("On a server: your full render distance, not just the server's view distance.",
			"#Chunks the server stops sending are kept while they're within your render distance (as they were when you last saw them; the server sends them "
				+ "again when you come back), so the view fills in as you travel. Single player already sends everything. Uses memory for the extra chunks.")).build();
		onRefresh(() -> self[0].setMessage(keepChunksLabel()));
		return self[0];
	}

	private static Component keepChunksLabel() {
		int kept = dev.cadence.shaders.cadence.server.ServerChunks.kept();
		return Component.literal("Keep Server Chunks: " + (dev.cadence.shaders.cadence.server.ServerChunks.on() ? kept > 0 ? "On (" + kept + ")" : "On" : "Off"));
	}

	private static Component autoRdLabel() {
		int held = dev.cadence.shaders.cadence.render.AutoRenderDistance.holding();
		return Component.literal("Auto Render Distance: " + (dev.cadence.shaders.cadence.render.AutoRenderDistance.on() ? held > 0 ? "On (" + held + ")" : "On" : "Off"));
	}

	public static Button dynamicLightsButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(dynamicLightsLabel(), b -> {
			DynamicLights.cycle();
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Light from torches and other light sources that move, as a placed one would give.",
			"Held Items|a torch, lantern or glowstone in anyone's hand lights the world around them",
			"All|also dropped light items, burning mobs, blazes, fireballs, lit TNT, glowing items in frames",
			"Off|as vanilla",
			"#Costs almost nothing (lit on the graphics chip). With a shader pack, the pack's own held light applies.")).build();
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

	/** Java (dev.cadence.metal.Vivace): the launcher's Java or Vivace, from the next launch; saved as java=. */
	public static Button javaButton() {
		java.nio.file.Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		boolean installed = dev.cadence.metal.Vivace.installed() != null;
		Button[] self = new Button[1];
		self[0] = Button.builder(javaLabel(gameDir), b -> {
			boolean on = !wantsVivace(gameDir);
			ShaderToggle.saveKey("java", on ? "vivace" : "launcher");
			if (on) dev.cadence.metal.Vivace.clearFailure(gameDir);
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Which Java runs Minecraft, from the next launch.",
			"Launcher's|the Java your launcher chose",
			"Vivace|Cadence's companion Java build: shorter garbage-collection pauses",
			"#Vivace is a separate, free download (open source, GPLv2 with the Classpath Exception). Cadence only starts it when you"
				+ " choose it here, and falls back to the launcher's Java if it can't.")).build();
		onRefresh(() -> self[0].setMessage(javaLabel(gameDir)));
		boolean byLauncher = dev.cadence.metal.Vivace.running() && System.getenv("CADENCE_JAVA") == null;
		String source = dev.cadence.metal.Vivace.settingSource(gameDir);
		if (source != null) {
			// (that setting wins over this button's: a click would change nothing)
			self[0].active = false;
			self[0].setTooltip(Tips.of("Set by " + source + ".", "#Change or remove it there to use this button."));
		} else if (byLauncher) {
			self[0].active = false;
			self[0].setTooltip(Tips.of("Your launcher starts Minecraft on Vivace itself (its Java setting)."));
		} else if (!installed && !wantsVivace(gameDir)) {
			self[0].active = false;
			self[0].setTooltip(Tips.of("Vivace, Cadence's companion Java build, isn't installed.",
				"#It's a separate download (see the Cadence page). Put its .jdk folder in ~/Library/Application Support/Vivace"
					+ " (or ~/Library/Java/JavaVirtualMachines) and this button turns on."));
		}
		return self[0];
	}

	private static boolean wantsVivace(java.nio.file.Path gameDir) {
		String s = dev.cadence.metal.Vivace.setting(gameDir);
		return !(s.isEmpty() || s.equalsIgnoreCase("launcher") || s.equalsIgnoreCase("off") || s.equalsIgnoreCase("false"));
	}

	private static Component javaLabel(java.nio.file.Path gameDir) {
		if (dev.cadence.metal.Vivace.running() && System.getenv("CADENCE_JAVA") == null) return Component.literal("Java: Vivace");
		return Component.literal("Java: " + (wantsVivace(gameDir) ? "Vivace" : "Launcher's"));
	}

	/** The status line about Java: a restart to switch, or why Vivace isn't in use; null when nothing to say. */
	private static Component javaStatus() {
		java.nio.file.Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		boolean want = wantsVivace(gameDir), on = dev.cadence.metal.Vivace.running(), ours = System.getenv("CADENCE_JAVA") != null;
		String note = dev.cadence.metal.Vivace.note();
		if (want && !on && !note.isEmpty()) return Component.literal(note).withStyle(ChatFormatting.YELLOW);
		if (want && !on) return Component.literal("Restart Minecraft to play on Vivace").withStyle(ChatFormatting.YELLOW);
		if (!want && on && ours) return Component.literal("Restart Minecraft to play on the launcher's Java").withStyle(ChatFormatting.YELLOW);
		return null;
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
		// (Render Max FPS: Heat Control, On Battery and Max Framerate don't apply, so their holds aren't shown)
		if (dev.cadence.shaders.cadence.render.MaxFpsMode.chosen())
			lines.add(Component.literal("Render Max FPS: nothing holds the frame rate back").withStyle(ChatFormatting.YELLOW));
		else if (HeatControl.level() > 0) lines.add(Component.literal(HeatControl.shortDescribe()).withStyle(ChatFormatting.YELLOW));
		// Retina changes at the next launch (a preset or the Retina button): said here too, so it's not missed
		if (ShaderToggle.retinaConfigured() != dev.cadence.metal.Retina.on())
			lines.add(Component.literal("Restart Minecraft to turn Retina " + (ShaderToggle.retinaConfigured() ? "on" : "off")).withStyle(ChatFormatting.YELLOW));
		if (MemoryGuard.level() > 0) lines.add(Component.literal(MemoryGuard.shortDescribe()).withStyle(ChatFormatting.YELLOW));
		// on battery: how long it lasts at the Mac's draw now (and Low Power Mode, which Cadence follows as Battery Saver)
		String battery = dev.cadence.metal.MacSystem.batteryTimeLeft();
		// (Low Power Mode said only while it saves: with On Battery Off it doesn't, BH4-15)
		boolean lpm = dev.cadence.shaders.cadence.system.BatteryMode.lowPower() && dev.cadence.shaders.cadence.system.BatteryMode.saving();
		if (!battery.isEmpty() || lpm)
			lines.add(Component.literal((battery.isEmpty() ? "" : "Battery: about " + battery + " left at this rate")
				+ (lpm ? (battery.isEmpty() ? "" : " · ") + "Low Power Mode: saving like Battery Saver" : ""))
				.withStyle(ChatFormatting.AQUA));
		Component farTerrain = FarTerrainScreen.packNote();
		if (farTerrain != null) lines.add(farTerrain);
		Component java = javaStatus();
		if (java != null) lines.add(java);
		// other mods that hook what Cadence replaces: those areas run vanilla's code for them (CompatLayers; the log has the detail)
		List<String> mods = dev.cadence.metal.compat.CompatLayers.mods();
		if (!mods.isEmpty()) lines.add(Component.literal("Works alongside: " + String.join(" · ", mods)).withStyle(ChatFormatting.AQUA));
		return lines;
	}
}
