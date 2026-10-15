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
		return new ConfirmScreen(yes -> {
			if (yes) start(parent);
			else {
				QuickSetup.done(mac);
				Minecraft.getInstance().gui.setScreen(parent);
			}
		}, Component.literal("Set up Cadence for your Mac"),
			Component.literal("Cadence checks what your " + name + " can do and picks the best settings for it. Takes about 15 seconds."),
			Component.literal("Set Up"), Component.literal("Not Now"));
	}

	public static void start(Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new GenericMessageScreen(Component.literal("Setting up Cadence...")));
		Thread t = new Thread(() -> {
			QuickSetup.Result r = QuickSetup.run(stage -> mc.execute(() -> mc.gui.setScreen(new GenericMessageScreen(Component.literal(stage)))));
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
			return new ConfirmScreen(ok -> {
				QuickSetup.done(r.mac());
				mc.gui.setScreen(parent);
			}, Component.literal("Cadence is set up"), msg, Component.literal("Done"), Component.literal("Back"));
		}
		msg.append(Component.literal("\n"));
		for (QuickSetup.Change c : r.changes())
			msg.append(Component.literal("\n" + c.what() + ": " + c.from() + " → ").append(Component.literal(c.to()).withStyle(ChatFormatting.GREEN))
				.append(Component.literal("  " + c.why()).withStyle(ChatFormatting.GRAY)));
		return new ConfirmScreen(apply -> {
			if (apply) QuickSetup.apply(r);
			else QuickSetup.done(r.mac());
			mc.gui.setScreen(parent);
		}, Component.literal("Recommended for your Mac"), msg, Component.literal("Apply"), Component.literal("Keep My Settings"));
	}
}
