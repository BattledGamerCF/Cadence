package dev.cadence.shaders.cadence.system;

import dev.cadence.shaders.cadence.ui.ShaderToggle;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * Cadence: what Cadence says in game. One short chat line when you join a world or server (grey, the few words that matter
 * in the green vanilla uses for a LAN port), and the corner notices (Notices). Both can be turned off from that line
 * ("[Hide these messages]", a client-side /cadence command that never reaches a server) or from Video Settings; once off,
 * the join line isn't shown either. Saved in Cadence's config ("messages").
 */
public final class CadenceMessages {
	private static volatile boolean enabled = !"false".equalsIgnoreCase(
		System.getProperty("cadence.messages", ShaderToggle.configValue("messages", "true")).strip());

	private CadenceMessages() {
	}

	public static boolean enabled() {
		return enabled;
	}

	public static void setEnabled(boolean on) {
		enabled = on;
		ShaderToggle.saveKey("messages", Boolean.toString(on));
	}

	/** A problem found while the game was starting (a shader pack that couldn't load), told once at the first join. */
	private static volatile String launchFailure;

	public static void launchFailure(String message) {
		launchFailure = message;
	}

	/** The connection the join line was last shown on: a server network logs you in again at every lobby or game switch. */
	private static java.lang.ref.WeakReference<Object> shownOn = new java.lang.ref.WeakReference<>(null);

	/**
	 * ClientPacketListener.handleLogin (joining a world or a server; not a dimension change), render thread. connection: the
	 * network connection it came on; the join line is shown once per connection (a server network sends a login packet at every
	 * switch between its lobbies and games: it was repeated each time).
	 */
	public static void joined(Object connection) {
		String failure = launchFailure;
		if (failure != null) {
			launchFailure = null;
			// (always shown, even with Cadence messages off: it explains why shaders are missing)
			say(dev.cadence.metal.system.Lang.literal("Cadence: " + failure).withStyle(net.minecraft.ChatFormatting.RED));
		}
		if (!enabled || connection != null && shownOn.get() == connection) return;
		shownOn = new java.lang.ref.WeakReference<>(connection);
		say(joinLine());
	}

	private static Component joinLine() {
		MutableComponent line = grey("Cadence: ");
		line.append(green("optimizing your game automatically", "Cadence picks settings for your Mac (Quick Setup) and adjusts "
			+ "resolution, frame rate and shadows while you play to keep it smooth and cool (Cooling: "
			+ HeatControl.cooling().label + "). Everything can be changed in Video Settings.", null));
		line.append(grey(". "));
		line.append(green("[Hide these messages]", "Turns off this line and Cadence's pop-ups in the corner. "
			+ "Turn them back on in Video Settings.", "/cadence messages off"));
		return line;
	}

	/**
	 * A command typed or clicked: true when it is Cadence's (handled here, never sent to the server). "cadence messages
	 * off|on".
	 */
	public static boolean command(String command, net.minecraft.client.multiplayer.ClientPacketListener connection) {
		String c = command.strip().toLowerCase(Locale.ROOT);
		if (c.startsWith("/")) c = c.substring(1);
		if (!c.equals("cadence") && !c.startsWith("cadence ")) return false;
		// (a server with its own /cadence command, another mod's: everything but Cadence's two exact commands goes to it)
		boolean serverHasIt = connection != null && connection.getCommands().getRoot().getChild("cadence") != null;
		if (serverHasIt && !c.equals("cadence messages off") && !c.equals("cadence messages on")) return false;
		switch (c) {
			case "cadence messages off" -> {
				setEnabled(false);
				say(grey("Cadence messages are off. Turn them back on in Video Settings."));
			}
			case "cadence messages on" -> {
				setEnabled(true);
				say(grey("Cadence messages are on."));
			}
			default -> say(grey("Cadence: /cadence messages off | on"));
		}
		return true;
	}

	private static void say(Component line) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.gui != null) mc.gui.chatListener().handleSystemMessage(line, false);
		});
	}

	private static MutableComponent grey(String text) {
		return dev.cadence.metal.system.Lang.literal(text).withStyle(ChatFormatting.GRAY);
	}

	private static MutableComponent green(String text, String hover, String command) {
		MutableComponent c = dev.cadence.metal.system.Lang.literal(text).withStyle(ChatFormatting.GREEN);
		c.withStyle(s -> {
			var styled = s.withHoverEvent(new HoverEvent.ShowText(dev.cadence.metal.system.Lang.literal(hover)));
			return command == null ? styled : styled.withClickEvent(new ClickEvent.RunCommand(command));
		});
		return c;
	}
}
