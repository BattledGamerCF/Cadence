package dev.cadence.shaders.cadence.setup;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Cadence Quick Setup's screens, in vanilla's own style: the offer, a short "checking" message, and the recommendations. */
public final class QuickSetupScreens {
	private QuickSetupScreens() {
	}

	/** The one-time offer (title screen) or the Video Settings button's start. */
	public static Screen offer(Screen parent) {
		QuickSetup.Mac mac = QuickSetup.detect();
		String name = mac.product().isEmpty() ? "Mac" : mac.product();
		return new CadenceConfirmScreen(yes -> {
			if (yes) Minecraft.getInstance().gui.setScreen(goal(parent));
			else {
				QuickSetup.done(mac);
				Minecraft.getInstance().gui.setScreen(parent);
			}
		}, Component.literal("Set up Cadence for your Mac"),
			Component.literal("Cadence optimizes Minecraft for your " + name + " automatically: it adjusts resolution, frame rate and "
				+ "shadows while you play to keep the game smooth and your Mac cool.\nSet Up checks what your Mac can do (about 15 "
				+ "seconds) and recommends settings. Nothing changes until you press Apply."),
			Component.literal("Set Up"), Component.literal("Not Now"));
	}

	/** What matters more: steady and cool, or every frame the Mac can make. */
	public static Screen goal(Screen parent) {
		return new CadenceConfirmScreen(smooth -> start(parent, smooth ? QuickSetup.Goal.SMOOTH : QuickSetup.Goal.MAX_FPS),
			Component.literal("What matters more to you?"),
			Component.literal("Smooth & Cool: the frame rate matches your screen, your Mac stays cooler and quieter, and the game "
				+ "stays steady for long sessions.\nMax FPS: as many frames as your Mac can make; it runs warmer"
				+ (QuickSetup.detect().fanless() ? "" : " and the fans work harder") + "."),
			Component.literal("Smooth & Cool"), Component.literal("Max FPS"));
	}

	public static void start(Screen parent) {
		start(parent, QuickSetup.Goal.SMOOTH);
	}

	public static void start(Screen parent, QuickSetup.Goal goal) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new GenericMessageScreen(Component.literal("Setting up Cadence...")));
		Thread t = new Thread(() -> {
			QuickSetup.Result r = QuickSetup.run(stage -> mc.execute(() -> mc.gui.setScreen(new GenericMessageScreen(Component.literal(stage)))), goal);
			mc.execute(() -> mc.gui.setScreen(results(r, parent)));
		}, "cadence-quick-setup");
		t.setDaemon(true);
		t.start();
	}

	public static Screen results(QuickSetup.Result r, Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		MutableComponent msg = Component.literal(r.mac().describe()).withStyle(ChatFormatting.GRAY);
		if (!r.measured()) msg.append(Component.literal("\n(Your Mac was warm, so Cadence used its built-in profile.)").withStyle(ChatFormatting.GRAY));
		if (r.changes().isEmpty()) {
			msg.append(Component.literal("\n\nYour settings already suit this Mac."));
			return new CadenceConfirmScreen(ok -> {
				QuickSetup.done(r.mac());
				mc.gui.setScreen(parent);
			}, Component.literal("Cadence is set up"), msg, Component.literal("Done"), Component.literal("Back"));
		}
		msg.append(Component.literal("\n"));
		for (QuickSetup.Change c : r.changes())
			msg.append(Component.literal("\n" + c.what() + ": " + c.from() + " → ").append(Component.literal(c.to()).withStyle(ChatFormatting.GREEN))
				.append(Component.literal("  " + c.why()).withStyle(ChatFormatting.GRAY)));
		return new CadenceConfirmScreen(apply -> {
			if (apply) QuickSetup.apply(r);
			else QuickSetup.done(r.mac());
			mc.gui.setScreen(parent);
		}, Component.literal("Recommended for your Mac"), msg, Component.literal("Apply"), Component.literal("Keep My Settings"), 460);
	}
}
