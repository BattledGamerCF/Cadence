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
		BATTERY("Battery Saver"), BALANCED("Balanced"), QUALITY("Max Quality"), CUSTOM("Custom");

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
			case CUSTOM -> {
			}
		}
	}

	private static void setShadows(int rate) {
		ShadowReuse.setRate(rate);
		ShaderToggle.saveKey("shadowRate", Integer.toString(rate));
	}

	/** After any change: every Cadence button's label follows. */
	static void refreshAll() {
		for (Runnable r : refreshers) r.run();
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

	private static Component tip(String text) {
		return Component.literal(text);
	}

	public static Button presetButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(presetLabel(), b -> {
			Preset now = current();
			Preset next = switch (now) {
				case BATTERY -> Preset.BALANCED;
				case BALANCED -> Preset.QUALITY;
				case QUALITY, CUSTOM -> Preset.BATTERY;
			};
			apply(next);
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tooltip.create(tip(
			"One click sets everything below.\n"
				+ "Battery Saver: coolest and quietest; the world may look softer when the Mac is busy (aims for 40 fps).\n"
				+ "Balanced (recommended): smooth 60 fps, sharp when possible, eases off when the Mac gets warm.\n"
				+ "Max Quality: always full sharpness and every-frame shadows; the Mac may get hot and slow itself down.\n"
				+ "Custom: you changed something below by hand.\n"
				+ "Tip: set Max Framerate to 60 (or 120 if your screen is ProMotion) unless you need more; extra frames your "
				+ "screen can't show only make heat.\n"
				+ "Java memory manager: " + dev.cadence.metal.JvmDefaults.summary() + ". Cadence starts Java with the settings measured "
				+ "best for 8 GB Macs; any Java arguments you set in your launcher win over them."))).build();
		onRefresh(() -> self[0].setMessage(presetLabel()));
		return self[0];
	}

	private static Component presetLabel() {
		return Component.literal("Performance: " + current().label);
	}

	public static Button heatButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(heatLabel(), b -> {
			HeatControl.setEnabled(!HeatControl.enabled());
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tooltip.create(tip(
			"Keeps your Mac at a steady temperature, so the game stays smooth instead of fast at first and sluggish later.\n"
				+ "When your Mac warms up, Cadence lowers the frame rate a little at a time until it stops heating, and raises it "
				+ "again slowly once it has cooled. If it gets hot, shadows and (with shaders) resolution ease off too.\n"
				+ "Off: always your full settings, but macOS may slow the Mac down when it's hot.\n"
				+ "Your Mac right now: " + HeatControl.thermalWords() + "."))).build();
		onRefresh(() -> self[0].setMessage(heatLabel()));
		return self[0];
	}

	private static Component heatLabel() {
		if (HeatControl.level() > 0) return Component.literal("Heat Control: Easing Off").withStyle(ChatFormatting.YELLOW);
		return Component.literal("Heat Control: " + (HeatControl.enabled() ? "On" : "Off"));
	}

	/** Leaves (SmartLeaves): Detailed (vanilla) or Smart. */
	public static Button leavesButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(leavesLabel(), b -> {
			dev.cadence.shaders.cadence.mesh.SmartLeaves.setEnabled(!dev.cadence.shaders.cadence.mesh.SmartLeaves.enabled());
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tooltip.create(tip(
			"Smart: trees look the same from outside, but leaves buried deep inside a canopy aren't drawn. Forests get much "
				+ "lighter for your Mac (up to about twice as fast in a dense forest); through gaps you see a little less deep "
				+ "into the tree.\nDetailed: every leaf face, as in vanilla.\nOnly matters with See-Through Leaves on."))).build();
		onRefresh(() -> self[0].setMessage(leavesLabel()));
		return self[0];
	}

	private static Component leavesLabel() {
		return Component.literal("Leaves: " + (dev.cadence.shaders.cadence.mesh.SmartLeaves.enabled() ? "Smart" : "Detailed"));
	}

	/** Cadence Messages (CadenceMessages): the join line and the corner notices. */
	public static Button messagesButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(messagesLabel(), b -> {
			dev.cadence.shaders.cadence.system.CadenceMessages.setEnabled(!dev.cadence.shaders.cadence.system.CadenceMessages.enabled());
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tooltip.create(tip(
			"On: a short line in chat when you join, and a small pop-up in the corner whenever Cadence adjusts something "
				+ "for you (heat, memory). Off: Cadence stays quiet; what it's doing is still shown here in Video Settings."))).build();
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
		}).width(Button.DEFAULT_WIDTH).tooltip(Tooltip.create(tip(
			"When you switch to another app (or another full-screen space), Minecraft draws only "
				+ dev.cadence.shaders.cadence.system.BackgroundFps.FPS + " frames per second behind it instead of your full Max Framerate. "
				+ "The game, its sounds and your world keep running normally, and it's back to full speed the moment you return.\n"
				+ "Saves battery and heat while you're doing something else.\n"
				+ "Normal: always draws at your Max Framerate (like vanilla, which only slows down when minimized or after a minute "
				+ "without input)."))).build();
		onRefresh(() -> self[0].setMessage(backgroundLabel()));
		return self[0];
	}

	private static Component backgroundLabel() {
		return Component.literal("In Background: " + (dev.cadence.shaders.cadence.system.BackgroundFps.enabled()
			? dev.cadence.shaders.cadence.system.BackgroundFps.FPS + " fps" : "Normal"));
	}

	/** Check Pack for Speed-ups (PackCheck): the label says what is known about the pack in use. */
	public static Button packCheckButton() {
		Button[] self = new Button[1];
		self[0] = Button.builder(packCheckLabel(), b -> {
			if (dev.cadence.shaders.cadence.packcheck.PackCheck.running()) return;
			if (dev.cadence.shaders.runtime.ShaderRuntime.get() == null) return;
			dev.cadence.shaders.cadence.packcheck.PackCheck.start(CadenceSettings::refreshAll);
			refreshAll();
		}).width(Button.DEFAULT_WIDTH).tooltip(Tooltip.create(tip(
			"Tests whether this shader pack can use Cadence's faster shadows (skipping shadows nothing on screen can show; "
				+ "often 10-25% more fps). It looks around from where you stand and compares the pictures with the speed-up on and "
				+ "off; only if they are identical is it turned on, for this pack, its settings and this dimension.\n"
				+ "Takes about 10 seconds; the game is paused and your view is put back afterwards. Needs a world loaded with the "
				+ "shader pack on. Click again to re-check somewhere else."))).build();
		onRefresh(() -> self[0].setMessage(packCheckLabel()));
		return self[0];
	}

	private static Component packCheckLabel() {
		if (dev.cadence.shaders.cadence.packcheck.PackCheck.running())
			return Component.literal("Checking pack...").withStyle(ChatFormatting.YELLOW);
		var runtime = dev.cadence.shaders.runtime.ShaderRuntime.get();
		if (runtime == null || runtime.programs() == null)
			return Component.literal(net.minecraft.client.Minecraft.getInstance().level == null ? "Pack Speed-ups: No World" : "Pack Speed-ups: No Pack")
				.withStyle(ChatFormatting.GRAY);
		if (runtime.programs().shadowCullingEligibility.eligible()) return Component.literal("Pack Speed-ups: Built In");
		var saved = dev.cadence.shaders.cadence.packcheck.PackCheck.savedResult();
		if (saved == null) return Component.literal("Pack Speed-ups: Check Now");
		return switch (saved) {
			case SAFE -> Component.literal("Pack Speed-ups: On (checked)").withStyle(ChatFormatting.GREEN);
			case UNSAFE -> Component.literal("Pack Speed-ups: Not Safe").withStyle(ChatFormatting.GRAY);
			case INCONCLUSIVE -> Component.literal("Pack Speed-ups: Can't Tell").withStyle(ChatFormatting.GRAY);
		};
	}

	/** What Cadence is changing on its own right now, one short line each (a gray "nothing" line when nothing). */
	public static List<Component> status() {
		List<Component> lines = new ArrayList<>();
		if (RenderScale.isAuto() && RenderScale.get() < 0.995f)
			lines.add(Component.literal("Auto: world drawn at " + Math.round(RenderScale.get() * 100) + "% to keep it smooth")
				.withStyle(ChatFormatting.GRAY));
		if (HeatControl.level() > 0) lines.add(Component.literal(HeatControl.shortDescribe()).withStyle(ChatFormatting.YELLOW));
		if (MemoryGuard.level() > 0) lines.add(Component.literal(MemoryGuard.shortDescribe()).withStyle(ChatFormatting.YELLOW));
		return lines;
	}
}
