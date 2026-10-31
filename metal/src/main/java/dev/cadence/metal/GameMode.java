package dev.cadence.metal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Cadence: macOS Game Mode for Minecraft. In Game Mode macOS gives the full-screen game priority on the CPU and GPU, holds back
 * background work and doubles the Bluetooth polling rate (lower latency for AirPods and controllers), but only for a program
 * that says it is a game in its app bundle's Info.plist, and it decides that once, when the process starts. Java started by a
 * launcher is an anonymous program, so it never gets Game Mode.
 *
 * <p>So JvmDefaults' restart at the very start of loading (which happens anyway, to add Cadence's Java options) starts the game
 * again as a new process from an app bundle that says it is a game: ~/Library/Application Support/Cadence/Game/&lt;id&gt;/Minecraft.app,
 * whose Contents/MacOS/java is a copy of the same Java's launcher (its signature unchanged) and whose other entries link to that
 * Java's folders. Same Java, same options, nothing installed system-wide. The first process becomes Cadence Helper's "wait":
 * it hands the launcher's signals (quit, interrupt) to the game and exits with the game's exit code, so the launcher still sees
 * the process it started, from start to end. If that process is killed outright, the game notices within a second and quits
 * as if its window were closed (saving the world).
 *
 * <p>Checked with macOS's log (gamepolicyd): the game is "Found game ... LSSupportsGameMode (Info.plist)" and granted Game Mode
 * (ConsoleMode) once full screen; an in-place exec into the same bundle, or the keys embedded in the java binary, are ignored.
 * Off: -Dcadence.gameMode=false or cadence.gameMode=false in config/cadence.properties; anything that fails falls back to the
 * restart as before.
 */
final class GameMode {
	static final String BUNDLE = "Minecraft.app";
	/** Environment of the game started this way: the waiting first process's pid. */
	static final String PARENT_ENV = "CADENCE_GAME_PARENT";

	private GameMode() {
	}

	static boolean enabled(java.util.Properties config) {
		String p = System.getProperty("cadence.gameMode", config.getProperty("cadence.gameMode", "true"));
		return !"false".equalsIgnoreCase(p.strip());
	}

	/** The game started from the bundle, and the command the first process becomes to wait for it. */
	record Started(Process game, List<String> waiter) {
	}

	/**
	 * Starts the game from the bundle as a new process (argv[0] replaced by the bundle's launcher), or returns null when it can't
	 * (nothing was started then).
	 */
	static Started spawn(Path javaLauncher, List<String> argv, Map<String, String> env) {
		Path helper = dev.cadence.metal.neural.Helper.binary();
		if (helper == null || !Files.isExecutable(helper)) {
			System.out.println("[cadence] Game Mode: Cadence Helper is unavailable; restarting as before");
			return null;
		}
		Path launcher = wrap(javaLauncher);
		if (launcher == null) return null;
		try {
			List<String> command = new java.util.ArrayList<>(argv);
			command.set(0, launcher.toString());
			ProcessBuilder pb = new ProcessBuilder(command).inheritIO();
			pb.environment().clear();
			pb.environment().putAll(env);
			pb.environment().put(PARENT_ENV, Long.toString(ProcessHandle.current().pid()));
			Process game = pb.start();
			return new Started(game, List.of(helper.toString(), "wait", Long.toString(game.pid())));
		} catch (IOException | RuntimeException e) {
			System.out.println("[cadence] Game Mode: the game couldn't be started as a game (" + e + "); restarting as before");
			return null;
		}
	}

	/**
	 * In the game started by spawn: if the waiting first process is gone (the launcher killed it outright), quit as if the window
	 * were closed; if that hangs, end after 20 seconds.
	 */
	static void watchParent() {
		String env = System.getenv(PARENT_ENV);
		if (env == null) return;
		long parent;
		try {
			parent = Long.parseLong(env.strip());
		} catch (NumberFormatException e) {
			return;
		}
		Thread t = new Thread(() -> {
			while (true) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					return;
				}
				var p = ProcessHandle.of(parent);
				if (p.isPresent() && p.get().isAlive() && ProcessHandle.current().parent().map(h -> h.pid() == parent).orElse(false)) continue;
				System.out.println("[cadence] Game Mode: the launcher's process is gone; quitting");
				try {
					var mc = net.minecraft.client.Minecraft.getInstance();
					if (mc != null) mc.execute(mc::stop);
					Thread.sleep(20_000);
				} catch (Throwable ignored) {
				}
				Runtime.getRuntime().halt(0);
			}
		}, "cadence-game-mode-parent");
		t.setDaemon(true);
		t.start();
	}

	/** The bundled launcher for the Java whose launcher is javaLauncher (made or brought up to date first); null if it can't be. */
	static Path wrap(Path javaLauncher) {
		try {
			Path real = javaLauncher.toRealPath();
			Path home = real.getParent().getParent();
			if (!Files.isRegularFile(home.resolve("lib/libjli.dylib"))) return null;
			String id = Integer.toHexString(home.toString().hashCode());
			Path contents = Path.of(System.getProperty("user.home"), "Library/Application Support/Cadence/Game", id, BUNDLE, "Contents");
			Path launcher = contents.resolve("MacOS/java");
			Files.createDirectories(launcher.getParent());
			// (a Java updated or rolled back in place gets a new launcher: size and time compared; a temporary file of its own, so
			// two games starting at once don't trip over each other's copy)
			if (!Files.isRegularFile(launcher) || Files.size(launcher) != Files.size(real)
				|| !Files.getLastModifiedTime(launcher).equals(Files.getLastModifiedTime(real))) {
				Path tmp = Files.createTempFile(launcher.getParent(), "java", ".tmp");
				try {
					Files.copy(real, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
					Files.move(tmp, launcher, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
			// every other entry of the Java home, linked (the launcher finds its home as the folder above MacOS)
			try (Stream<Path> s = Files.list(home)) {
				for (Path p : (Iterable<Path>) s::iterator) {
					String name = p.getFileName().toString();
					if (name.equals("MacOS") || name.equals("Info.plist")) continue;
					Path link = contents.resolve(name);
					if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link).equals(p)) continue;
					if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(link)) continue;  // (not ours to replace)
					Files.deleteIfExists(link);
					try {
						Files.createSymbolicLink(link, p);
					} catch (java.nio.file.FileAlreadyExistsException made) {
						// (another game starting at the same moment made it)
					}
				}
			}
			Path plist = contents.resolve("Info.plist");
			if (!Files.isRegularFile(plist) || !Files.readString(plist, StandardCharsets.UTF_8).equals(PLIST)) {
				Path tmp = Files.createTempFile(contents, "Info", ".tmp");
				Files.writeString(tmp, PLIST, StandardCharsets.UTF_8);
				Files.move(tmp, plist, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			// (once per launcher: it must start, or the game would never get going; a failure leaves the bundle unused)
			Path ok = contents.resolve("started-" + Files.size(launcher) + "-" + Files.getLastModifiedTime(launcher).toMillis());
			if (!Files.exists(ok)) {
				// (its output discarded, not read: a read would wait past the timeout for a launcher that hangs)
				Process p = new ProcessBuilder(launcher.toString(), "-version").redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
					p.destroyForcibly();
					System.out.println("[cadence] Game Mode: the bundled Java didn't start; restarting as before");
					return null;
				}
				try (Stream<Path> old = Files.list(contents)) {
					for (Path o : (Iterable<Path>) old::iterator) if (o.getFileName().toString().startsWith("started-") && !o.equals(ok)) Files.deleteIfExists(o);
				}
				try {
					Files.createFile(ok);
				} catch (java.nio.file.FileAlreadyExistsException made) {
					// (checked by another game starting at the same moment)
				}
			}
			return launcher;
		} catch (IOException | RuntimeException | InterruptedException e) {
			System.out.println("[cadence] Game Mode: the bundle couldn't be made (" + e + "); restarting as before");
			return null;
		}
	}

	private static final String PLIST = """
		<?xml version="1.0" encoding="UTF-8"?>
		<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
		<plist version="1.0">
		<dict>
			<key>CFBundleIdentifier</key><string>dev.cadence.minecraft</string>
			<key>CFBundleName</key><string>Minecraft</string>
			<key>CFBundleExecutable</key><string>java</string>
			<key>CFBundlePackageType</key><string>APPL</string>
			<key>CFBundleShortVersionString</key><string>1.0</string>
			<key>LSApplicationCategoryType</key><string>public.app-category.games</string>
			<key>LSSupportsGameMode</key><true/>
			<key>NSHighResolutionCapable</key><true/>
		</dict>
		</plist>
		""";
}
