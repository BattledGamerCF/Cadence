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
		}, dev.cadence.metal.Lang.literal("Set up Cadence for your Mac"),
			dev.cadence.metal.Lang.literal("Cadence optimizes Minecraft for your " + name + " automatically: it adjusts resolution, frame rate and "
				+ "shadows while you play to keep the game smooth and your Mac cool.\nSet Up checks what your Mac can do (about 15 "
				+ "seconds) and recommends settings. Nothing changes until you press Apply."),
			dev.cadence.metal.Lang.literal("Set Up"), dev.cadence.metal.Lang.literal("Not Now"));
	}

	/** What matters more: steady and cool, or every frame the Mac can make. */
	public static Screen goal(Screen parent) {
		return new CadenceConfirmScreen(smooth -> start(parent, smooth ? QuickSetup.Goal.SMOOTH : QuickSetup.Goal.MAX_FPS),
			dev.cadence.metal.Lang.literal("What matters more to you?"),
			dev.cadence.metal.Lang.literal("Smooth & Cool: the frame rate matches your screen, your Mac stays cooler and quieter, and the game "
				+ "stays steady for long sessions.\nMax FPS: as many frames as your Mac can make; it runs warmer"
				+ (QuickSetup.detect().fanless() ? "" : " and the fans work harder") + "."),
			dev.cadence.metal.Lang.literal("Smooth & Cool"), dev.cadence.metal.Lang.literal("Max FPS"));
	}

	public static void start(Screen parent) {
		start(parent, QuickSetup.Goal.SMOOTH);
	}

	public static void start(Screen parent, QuickSetup.Goal goal) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new GenericMessageScreen(dev.cadence.metal.Lang.literal("Setting up Cadence...")));
		Thread t = new Thread(() -> {
			try {
				QuickSetup.Result r = QuickSetup.run(stage -> mc.execute(() -> mc.gui.setScreen(new GenericMessageScreen(dev.cadence.metal.Lang.literal(stage)))), goal);
				mc.execute(() -> mc.gui.setScreen(results(r, parent)));
			} catch (Throwable failure) {
				// (the "Setting up..." screen has no buttons: a test that throws must not leave the player on it)
				org.slf4j.LoggerFactory.getLogger("cadence").warn("Quick Setup failed", failure);
				mc.execute(() -> {
					mc.gui.setScreen(parent);
					dev.cadence.shaders.cadence.system.Notices.post("Quick Setup couldn't finish", "Nothing was changed (" + failure + ").");
				});
			}
		}, "cadence-quick-setup");
		t.setDaemon(true);
		t.start();
	}

	public static Screen results(QuickSetup.Result r, Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		MutableComponent msg = dev.cadence.metal.Lang.literal(r.mac().describe()).withStyle(ChatFormatting.GRAY);
		if (!r.measured()) msg.append(dev.cadence.metal.Lang.literal("\n(Your Mac was warm, so Cadence used its built-in profile.)").withStyle(ChatFormatting.GRAY));
		if (r.changes().isEmpty()) {
			msg.append(dev.cadence.metal.Lang.literal("\n\nYour settings already suit this Mac."));
			return new CadenceConfirmScreen(ok -> {
				QuickSetup.done(r.mac());
				mc.gui.setScreen(parent);
			}, dev.cadence.metal.Lang.literal("Cadence is set up"), msg, dev.cadence.metal.Lang.literal("Done"), dev.cadence.metal.Lang.literal("Back"));
		}
		msg.append(dev.cadence.metal.Lang.literal("\n"));
		for (QuickSetup.Change c : r.changes())
			msg.append(dev.cadence.metal.Lang.literal("\n" + c.what() + ": " + c.from() + " → ").append(dev.cadence.metal.Lang.literal(c.to()).withStyle(ChatFormatting.GREEN))
				.append(dev.cadence.metal.Lang.literal("  " + c.why()).withStyle(ChatFormatting.GRAY)));
		return new CadenceConfirmScreen(apply -> {
			if (apply) QuickSetup.apply(r);
			else QuickSetup.done(r.mac());
			mc.gui.setScreen(parent);
		}, dev.cadence.metal.Lang.literal("Recommended for your Mac"), msg, dev.cadence.metal.Lang.literal("Apply"), dev.cadence.metal.Lang.literal("Keep My Settings"), 460);
	}
}
