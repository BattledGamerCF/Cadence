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
 * <p>Commands are whole phrases ("please" and "Cadence" around them are fine; talk that only contains a command word does
 * nothing, BH7-07): "open inventory", "close", "jump", "screenshot", "take a photo", "save replay" / "clip that", "pause",
 * "hide hud" / "show hud", "first person" / "third person", "slot one".."nine", "next item" / "previous item". Chat and commands
 * need "Cadence" first: "Cadence say ..." (a chat message), "Cadence command ..." (e.g. "Cadence command time set day").
 *
 * <p>Each start uses its own phrase file (voice-PID-N.txt); the helper deletes it when it stops (the stop file, or the game's
 * process gone: it never outlives the game, BH7-04).
 */
public final class VoiceControl {
	private static volatile boolean wanted;
	private static volatile String status = "";
	private static Path out;
	private static long readAt;
	private static int session;
	private static boolean hooked;
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
			long pid = ProcessHandle.current().pid();
			out = dir().resolve("voice-" + pid + "-" + ++session + ".txt");
			Files.writeString(out, "");
			Files.deleteIfExists(dir().resolve("voice.txt"));  // (beta.23 before BH7: one shared file, kept)
			readAt = 0;
			if (!hooked) {
				hooked = true;
				Runtime.getRuntime().addShutdownHook(new Thread(VoiceControl::stop, "cadence-voice-stop"));
			}
			String locale = Minecraft.getInstance().options.languageCode.replace('_', '-');
			// (open: LaunchServices starts it as its own app, so the permissions are asked for Cadence Helper)
			new ProcessBuilder("/usr/bin/open", "-n", "-g", "-a", app.toString(), "--args", "listen", out.toString(), "--locale", locale,
				"--parent", String.valueOf(pid)).start().waitFor();
			running = true;
			status = "Starting";
		} catch (Exception e) {
			status = "Can't: " + e.getMessage();
			wanted = false;
		}
	}

	private static synchronized void stop() {
		if (!running) return;
		running = false;
		status = "";
		try {
			if (out != null) Files.writeString(out.resolveSibling(out.getFileName() + ".stop"), "");  // (the helper deletes both)
		} catch (Exception ignored) {
		}
		out = null;
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
		// (chat and commands only after the name: "Cadence say ...", never from talk that happens to start with "say", BH7-07)
		if (p.startsWith("cadence say ") && player != null) {
			String o = original.strip();
			int at = o.toLowerCase(Locale.ROOT).indexOf("say ");
			String msg = o.substring(at + 4).strip();
			player.connection.sendChat(msg);
			return "said \"" + msg + "\"";
		}
		for (String prefix : new String[] {"cadence command ", "cadence slash "}) {
			if (p.startsWith(prefix) && player != null) {
				String cmd = p.substring(prefix.length()).strip();
				player.connection.sendCommand(cmd);
				return "/" + cmd;
			}
		}
		p = bare(p);
		if (has(p, "open inventory", "open the inventory", "inventory")) {
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
				if (p.equals("slot " + numbers[i]) || p.equals("slot " + (i + 1))) {
					inv.setSelectedSlot(i);
					return "slot " + (i + 1);
				}
			}
		}
		return "";
	}

	/** The phrase without the polite words and the name around it ("cadence, please jump now" -> "jump"). */
	private static String bare(String p) {
		String[] around = {"cadence ", "hey cadence ", "please ", "can you ", "now ", "the "};
		boolean again = true;
		while (again) {
			again = false;
			for (String w : around) {
				if (p.startsWith(w)) {
					p = p.substring(w.length()).strip();
					again = true;
				}
			}
			for (String w : new String[] {" please", " now", " thanks", " thank you"}) {
				if (p.endsWith(w)) {
					p = p.substring(0, p.length() - w.length()).strip();
					again = true;
				}
			}
		}
		return p;
	}

	/** A whole-phrase match (BH7-07: a command word inside other talk does nothing). */
	private static boolean has(String p, String... phrases) {
		for (String s : phrases) if (p.equals(s)) return true;
		return false;
	}
}
