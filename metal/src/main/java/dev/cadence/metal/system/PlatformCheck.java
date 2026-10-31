// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.system;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.gui.FabricGuiEntry;
import net.fabricmc.loader.impl.gui.FabricStatusTree;
import net.fabricmc.loader.impl.util.Localization;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Stops the launch, before any Metal class loads, on a machine the native library can't run on: it is arm64-only and
 * the mod supports macOS 26 and later. The message goes to the log in one line and to Fabric's own error dialog (the one it
 * shows for a missing dependency), then the game exits. The dialog's title line doesn't wrap, so it gets only the short
 * "NAME can't run on this computer."; the reason and the fix go in its details area, one row each (rows wrap). A plain
 * exception from preLaunch would only show up as "A mod crashed on startup!" with the reason buried in a stack trace.
 * -Dcadence.platformCheck.fake=linux (or x86_64, or a macOS version like 15.5) pretends to be that platform, to test the message.
 */
final class PlatformCheck {
	private PlatformCheck() {
	}

	static void run() {
		String os = System.getProperty("os.name", ""), arch = System.getProperty("os.arch", ""), version = System.getProperty("os.version", "");
		String fake = System.getProperty("cadence.platformCheck.fake");
		if (fake != null) {
			if (fake.matches("[0-9.]+")) version = fake;
			else if (fake.equals("x86_64") || fake.equals("amd64") || fake.equals("rosetta")) arch = "x86_64";
			else os = fake;
		}
		String problem = problem(os, arch, version);
		if (problem == null) return;
		String name = modName();
		String fix = rosetta()
			// an Apple Silicon Mac running an Intel Java through Rosetta: the fix is a native Java, not removing the mod
			? "Install an Apple Silicon (arm64) Java 25, e.g. Azul Zulu (azul.com/downloads) or Eclipse Temurin (adoptium.net), "
				+ "choosing macOS ARM 64-bit, and select it in your launcher's Java settings. It also makes Minecraft much faster."
			: "To play on this computer, remove the " + (name.equals("Cadence") ? "cadence" : name) + " jar from the mods folder.";
		String message = name + " can't run here. " + problem + " " + fix;
		Log.error(LogCategory.GENERAL, message);
		try {
			FabricGuiEntry.displayError(name + " can't run on this computer.", null, tree -> details(tree, problem, fix, message), true); // exits the game
		} catch (Throwable t) {
			throw new IllegalStateException(message, t);
		}
		throw new IllegalStateException(message);
	}

	/**
	 * The dialog's details area: the reason and the fix in place of Fabric's "No further details available" row, and the
	 * "Copy error" button Fabric adds only when it builds the details itself (it copies the whole message, as logged).
	 */
	private static void details(FabricStatusTree tree, String problem, String fix, String message) {
		if (tree.tabs.isEmpty()) return;
		FabricStatusTree.FabricStatusNode node = tree.tabs.get(0).node;
		node.children.clear();
		node.addMessage(problem, FabricStatusTree.FabricTreeWarningLevel.ERROR);
		node.addMessage(fix, FabricStatusTree.FabricTreeWarningLevel.INFO);
		tree.addButton(Localization.format("gui.button.copyError"), FabricStatusTree.FabricBasicButtonType.CLICK_MANY).withClipboard(message);
	}

	/** The mod's name from its fabric.mod.json (what Fabric's own dialogs call it); builds may differ. */
	private static String modName() {
		try {
			return FabricLoader.getInstance().getModContainer("cadence").map(c -> c.getMetadata().getName()).orElse("Cadence");
		} catch (Throwable t) {
			return "Cadence";
		}
	}

	/** This Java runs translated by Rosetta on an Apple Silicon Mac (sysctl.proc_translated; the native library can't load here). */
	private static boolean rosetta() {
		if (!System.getProperty("os.name", "").startsWith("Mac")) return false;
		if ("rosetta".equals(System.getProperty("cadence.platformCheck.fake"))) return true;
		try {
			Process p = new ProcessBuilder("/usr/sbin/sysctl", "-n", "sysctl.proc_translated").redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes()).strip();
			p.waitFor();
			return out.equals("1");
		} catch (Exception e) {
			return false;
		}
	}

	private static String problem(String os, String arch, String version) {
		if (!os.startsWith("Mac")) return "It needs an Apple Silicon Mac (M1 or newer) with macOS 26 or later, and this computer runs " + os + ".";
		if (!arch.equals("aarch64") && !arch.equals("arm64")) {
			return "It needs an Apple Silicon Mac (M1 or newer) and an Apple Silicon (arm64) Java, and this Java is " + arch
				+ " (an Intel Mac, or an Intel Java running through Rosetta).";
		}
		int major;
		try {
			major = Integer.parseInt(version.split("\\.")[0]);
		} catch (NumberFormatException e) {
			return null; // unknown version string: let it try rather than block a working Mac
		}
		// A JDK built against an older SDK may see macOS 26 as "16.x" (Apple's compatibility numbering); there was no macOS 16.
		if (major < 26 && major != 16) return "It needs macOS 26 or later, and this Mac runs macOS " + version + ". Update macOS in System Settings > General > Software Update.";
		return null;
	}
}
