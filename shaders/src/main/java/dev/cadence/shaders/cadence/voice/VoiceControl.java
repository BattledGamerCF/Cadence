package dev.cadence.shaders.cadence.voice;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/**
 * Voice Control (Video Settings > Cadence: Apple Hardware): spoken commands. Cadence Helper (dev.cadence.metal.neural.Helper)
 * listens with Apple's speech recognition on this Mac only (requiresOnDeviceRecognition: the Neural Engine, nothing sent
 * anywhere) and appends each finished phrase to a file; each tick the new lines become game actions. The helper runs as its own
 * app (launched through LaunchServices), so the first time macOS asks to let Cadence Helper use the microphone and speech
 * recognition; turning Voice Control off stops it.
 *
 * <p>Commands (any order of polite words around them is fine): "open inventory", "close", "jump", "screenshot", "take a photo",
 * "save replay" / "clip that", "pause", "hide hud" / "show hud", "first person" / "third person", "slot one".."nine",
 * "next item" / "previous item", "say ..." (a chat message), "slash ..." / "command ..." (a command, e.g. "slash time set day").
 */
public final class VoiceControl {
	private static volatile boolean wanted;
	private static volatile String status = "";
	private static Path out;
	private static long readAt;
	private static boolean running;
	private static int jumpTicks;
	/** The last phrase heard and what it did, for the self-test and the button. */
	private static volatile String last = "";

	private VoiceControl() {
	}

	public static boolean wanted() {
		return wanted;
	}

	public static String status() {
		return status;
	}

	public static String last() {
		return last;
	}

	public static void setWanted(boolean on) {
		wanted = on;
		if (!on) stop();
	}

	private static Path dir() {
		return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Cadence");
	}

	private static void start() {
		Path app = dev.cadence.metal.neural.Helper.app();
		if (app == null) {
			status = "Can't: no Cadence Helper";
			wanted = false;
			return;
		}
		try {
			out = dir().resolve("voice.txt");
			Files.deleteIfExists(out.resolveSibling("voice.txt.stop"));
			Files.writeString(out, "");
			readAt = 0;
			String locale = Minecraft.getInstance().options.languageCode.replace('_', '-');
			// (open: LaunchServices starts it as its own app, so the permissions are asked for Cadence Helper)
			new ProcessBuilder("/usr/bin/open", "-n", "-g", "-a", app.toString(), "--args", "listen", out.toString(), "--locale", locale).start().waitFor();
			running = true;
			status = "Starting";
		} catch (Exception e) {
			status = "Can't: " + e.getMessage();
			wanted = false;
		}
	}

	private static void stop() {
		if (!running) return;
		running = false;
		status = "";
		try {
			if (out != null) Files.writeString(out.resolveSibling("voice.txt.stop"), "");
		} catch (Exception ignored) {
		}
	}

	/** Each client tick (ShaderKeys.tick): the helper started or stopped as the setting says, new phrases acted on. */
	public static void tick(Minecraft mc) {
		if (jumpTicks > 0 && --jumpTicks == 0) mc.options.keyJump.setDown(false);
		if (!wanted) return;
		if (!running) start();
		if (out == null) return;
		try (RandomAccessFile f = new RandomAccessFile(out.toFile(), "r")) {
			if (f.length() <= readAt) return;
			f.seek(readAt);
			byte[] b = new byte[(int) (f.length() - readAt)];
			f.readFully(b);
			String text = new String(b, StandardCharsets.UTF_8);
			int end = text.lastIndexOf('\n');
			if (end < 0) return;
			readAt += text.substring(0, end + 1).getBytes(StandardCharsets.UTF_8).length;
			for (String line : text.substring(0, end).split("\n")) handle(mc, line.strip());
		} catch (Exception e) {
			// (not written yet)
		}
	}

	/** One phrase: a status line from the helper (#...), or a command. Also the self-test's way in ("voice=..."). */
	public static void handle(Minecraft mc, String phrase) {
		if (phrase.isEmpty()) return;
		if (phrase.startsWith("#")) {
			status = phrase.equals("#listening") ? "Listening" : phrase.substring(1);
			return;
		}
		String p = phrase.toLowerCase(Locale.ROOT).replaceAll("[.,!?]", "").strip();
		String did = act(mc, p, phrase);
		last = phrase + " -> " + did;
		if (mc.player != null && !did.isEmpty()) mc.gui.hud.setOverlayMessage(Component.literal("Voice: " + did), false);
	}

	private static String act(Minecraft mc, String p, String original) {
		var player = mc.player;
		if (p.startsWith("say ") && player != null) {
			String msg = original.strip().substring(4).strip();
			player.connection.sendChat(msg);
			return "said \"" + msg + "\"";
		}
		for (String prefix : new String[] {"slash ", "command "}) {
			if (p.startsWith(prefix) && player != null) {
				String cmd = p.substring(prefix.length()).strip();
				player.connection.sendCommand(cmd);
				return "/" + cmd;
			}
		}
		if (has(p, "open inventory", "inventory")) {
			if (player != null) mc.gui.setScreen(new InventoryScreen(player));
			return "inventory";
		}
		if (has(p, "close menu", "close", "back to game", "resume")) {
			mc.gui.setScreen(null);
			return "closed";
		}
		if (has(p, "jump")) {
			mc.options.keyJump.setDown(true);
			jumpTicks = 2;
			return "jump";
		}
		if (has(p, "screenshot")) {
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(), m -> mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(m)));
			return "screenshot";
		}
		if (has(p, "take a photo", "photo mode", "photo")) {
			dev.cadence.shaders.cadence.render.PhotoMode.take(mc);
			return "photo";
		}
		if (has(p, "save replay", "clip that", "save that")) {
			dev.cadence.shaders.cadence.ui.ShaderKeys.saveReplay(mc);
			return "replay saved";
		}
		if (has(p, "pause")) {
			mc.pauseGame(false);
			return "paused";
		}
		if (has(p, "hide hud", "hide the hud")) {
			if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			return "HUD hidden";
		}
		if (has(p, "show hud", "show the hud")) {
			if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			return "HUD shown";
		}
		if (has(p, "third person")) {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			return "third person";
		}
		if (has(p, "first person")) {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			return "first person";
		}
		if (player != null) {
			var inv = player.getInventory();
			if (has(p, "next item")) {
				inv.setSelectedSlot((inv.getSelectedSlot() + 1) % 9);
				return "slot " + (inv.getSelectedSlot() + 1);
			}
			if (has(p, "previous item", "last item")) {
				inv.setSelectedSlot((inv.getSelectedSlot() + 8) % 9);
				return "slot " + (inv.getSelectedSlot() + 1);
			}
			String[] numbers = {"one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};
			for (int i = 0; i < 9; i++) {
				if (p.contains("slot " + numbers[i]) || p.contains("slot " + (i + 1))) {
					inv.setSelectedSlot(i);
					return "slot " + (i + 1);
				}
			}
		}
		return "";
	}

	private static boolean has(String p, String... phrases) {
		for (String s : phrases) if (p.equals(s) || p.contains(s)) return true;
		return false;
	}
}
