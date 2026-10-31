package dev.cadence.metal.system;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Cadence: playing on Vivace, the companion Java build (shorter garbage-collection pauses; a separate download, GPLv2 with the
 * Classpath Exception), when the player asks for it. Off by default: the player turns it on with Video Settings > Java, which
 * takes effect at the next launch.
 *
 * <p>With it on, at the very start of loading JvmDefaults restarts the game (execve: same process, same command line) on the
 * newest installed Vivace instead of the launcher's Java. Cadence only runs a program the player installed; it downloads
 * nothing, ships no part of Vivace, and uses only public interfaces (exec, the release file, -version), so the two programs stay
 * separate (research/LEGAL2.md in the Vivace project).
 *
 * <p>Found in ~/Library/Application Support/Vivace (macOS doesn't treat Java there as a system Java), in the folders macOS
 * lists Java in (~/Library/Java/JavaVirtualMachines and /Library/Java/JavaVirtualMachines), or at the path cadence.java=
 * names (a .jdk folder, a Java home or its bin/java). A Vivace is a folder whose release file says
 * IMPLEMENTOR="Vivace" (or named Vivace-*); it must be Java 25 or newer (what the game needs). The full JDK is preferred over
 * the game runtime when both are there (it has every module a mod might use).
 *
 * <p>Safety: before switching, the chosen Java is started once with the player's -XX options and Cadence's and -version (a
 * fraction of a second); if it refuses one (an option a newer Java removed) or macOS blocks it (a download still in
 * quarantine), the game carries on with the launcher's Java and says why. A start on Vivace that never reached Cadence (the
 * pending note was not cleared) turns the switch off for that Vivace until it changes or the player turns it on again.
 * Settings: -Dcadence.java=, cadence.java= in config/cadence.properties, or java= in config/cadence-shaders.properties (the
 * button's), first found wins; values vivace / launcher / a path.
 */
public final class Vivace {
	/** Environment of a process Cadence restarted on Vivace: which java. */
	static final String ENV = "CADENCE_JAVA";
	private static final String STATE = "cadence-java.properties";
	private static volatile String note = "";
	/** Options the chosen Java said it ignores ("support was removed"), from the probe: left out of the restart. */
	private static final Set<String> removed = new HashSet<>();
	private static final Pattern REMOVED = Pattern.compile("Ignoring option (\\w+); support was removed");

	private Vivace() {
	}

	/** Whether this game is running on Vivace (however it was started). */
	public static boolean running() {
		return "Vivace".equals(System.getProperty("java.vendor"));
	}

	/** One short line about Vivace for the settings screen: why it isn't in use, or "" when nothing to say. */
	public static String note() {
		return note;
	}

	/** The newest installed Vivace's java, or null. */
	public static Path installed() {
		List<Path> homes = new ArrayList<>();
		String home = System.getProperty("user.home", "");
		// (Application Support/Vivace: a place macOS doesn't list as a system Java, so Terminal's `java` stays as it was)
		for (Path dir : List.of(Path.of(home, "Library/Application Support/Vivace"), Path.of(home, "Library/Java/JavaVirtualMachines"),
			Path.of("/Library/Java/JavaVirtualMachines"))) {
			if (!Files.isDirectory(dir)) continue;
			try (Stream<Path> s = Files.list(dir)) {
				s.map(p -> p.resolve("Contents/Home")).filter(Vivace::isVivace).forEach(homes::add);
			} catch (IOException ignored) {
			}
		}
		// newest version first; at the same version the full JDK (it has a jmods folder or javac) before the game runtime
		return homes.stream().max(Comparator.comparing((Path h) -> version(release(h)))
				.thenComparing(h -> Files.exists(h.resolve("bin/javac"))))
			.map(h -> h.resolve("bin/java")).orElse(null);
	}

	/** Where the setting comes from when it isn't the Video Settings button's ("-Dcadence.java", ...), or null. */
	public static String settingSource(Path gameDir) {
		if (System.getProperty("cadence.java") != null) return "-Dcadence.java in your Java arguments";
		if (read(gameDir.resolve("config"), Legacy.CONFIG, Legacy.CONFIG_OLD).getProperty("cadence.java") != null)
			return "cadence.java in config/cadence.properties";
		return null;
	}

	/** "vivace", "launcher", or a path: the setting, from the first place that has one. */
	public static String setting(Path gameDir) {
		String p = System.getProperty("cadence.java");
		if (p != null) return p.strip();
		String v = read(gameDir.resolve("config"), Legacy.CONFIG, Legacy.CONFIG_OLD).getProperty("cadence.java");
		if (v != null) return v.strip();
		return read(gameDir.resolve("config"), Legacy.SHADERS_CONFIG, Legacy.SHADERS_CONFIG_OLD).getProperty("java", "launcher").strip();
	}

	/** The environment variable that carries the note into the restarted process (the note is per process). */
	static final String NOTE_ENV = "CADENCE_JAVA_NOTE";

	/** At startup, from JvmDefaults, on a Java Cadence restarted: a start on Vivace got this far (its pending note goes). */
	static void reached(Path gameDir) {
		// (why Vivace wasn't used was decided in the process before the restart: shown here, where the settings screen reads it)
		String carried = System.getenv(NOTE_ENV);
		if (carried != null && !carried.isEmpty()) note = carried;
		if (System.getenv(ENV) == null) return;
		Properties st = read(gameDir.resolve("config"), STATE, STATE);
		if (st.remove("pending") != null) save(st, gameDir.resolve("config").resolve(STATE));
	}

	/** The restart onto Vivace failed (execve returned): no pending note, the game carries on as launched. */
	static void execFailed(Path gameDir) {
		Properties st = read(gameDir.resolve("config"), STATE, STATE);
		if (st.remove("pending") != null) save(st, gameDir.resolve("config").resolve(STATE));
		note = "Vivace couldn't be started: playing on the launcher's Java";
	}

	/** At startup, from JvmDefaults: the java to restart on (null to stay), given the -XX options the restart will carry. */
	static Path target(Path gameDir, List<String> input, List<String> add) {
		if (running()) return null;
		Path state = gameDir.resolve("config").resolve(STATE);
		Properties st = read(gameDir.resolve("config"), STATE, STATE);
		String want = setting(gameDir);
		if (want.isEmpty() || want.equalsIgnoreCase("launcher") || want.equalsIgnoreCase("off") || want.equalsIgnoreCase("false")) return null;
		Path java = want.equalsIgnoreCase("vivace") || want.equalsIgnoreCase("on") || want.equalsIgnoreCase("true") ? installed() : javaAt(want);
		if (java == null) {
			note = want.equalsIgnoreCase("vivace") ? "Vivace isn't installed: playing on the launcher's Java" : "No Java at cadence.java=" + want;
			return null;
		}
		String stamp = java + "@" + modified(java);
		// a start on that Java that never got this far: once is enough
		String pending = st.getProperty("pending");
		if (pending != null) {
			st.remove("pending");
			st.setProperty("failed", pending);
			save(st, state);
		}
		if (stamp.equals(st.getProperty("failed"))) {
			note = "Vivace didn't start last time: playing on the launcher's Java (turn it on again to retry)";
			return null;
		}
		Properties rel = release(java.getParent().getParent());
		int feature = version(rel).feature();
		if (feature < 25) {
			note = "Vivace " + rel.getProperty("JAVA_VERSION", "?") + " is too old for this Minecraft (needs Java 25)";
			return null;
		}
		String refused = probe(java, input, add);
		if (refused != null) {
			// (a Vivace that hangs or that macOS stops costs 20 s of every launch: not again until it changes or the player
			// turns the switch on again)
			if (refused == TIMEOUT || refused == STOPPED) {
				st.setProperty("failed", stamp);
				save(st, state);
			}
			note = refused;
			System.out.println("[cadence] Vivace: not used (" + refused + ")");
			return null;
		}
		st.setProperty("pending", stamp);
		save(st, state);
		return java;
	}

	/** The Java version (feature number) of the Java home this java program is in; 0 when unknown. */
	static int feature(Path java) {
		return version(release(java.getParent().getParent())).feature();
	}

	/** Names of options the chosen Java ignores (removed in its version), from the probe. */
	static Set<String> removedOptions() {
		return removed;
	}

	/** After the player turns the switch back on: forget a failed start. */
	public static void clearFailure(Path gameDir) {
		Path state = gameDir.resolve("config").resolve(STATE);
		Properties st = read(gameDir.resolve("config"), STATE, STATE);
		if (st.remove("failed") != null) save(st, state);
	}

	/** Starts java once with the options the game will get: null when it starts, else why not. */
	private static String probe(Path java, List<String> input, List<String> add) {
		List<String> cmd = new ArrayList<>();
		cmd.add(java.toString());
		// only the -XX options (what differs between Java builds and versions); never ones with side effects outside the
		// probe (pre-touching the whole heap, a flight recording, a flags file)
		for (List<String> list : List.of(add, input)) for (String a : list) {
			if (!a.startsWith("-XX:")) continue;
			if (a.contains("AlwaysPreTouch") || a.startsWith("-XX:StartFlightRecording") || a.startsWith("-XX:FlightRecorderOptions")
				|| a.startsWith("-XX:Flags=") || a.startsWith("-XX:VMOptionsFile")) continue;
			cmd.add(a);
		}
		cmd.add("-version");
		Path log = null;
		try {
			// (output to a file, and the wait first: reading the pipe first blocked for as long as the java stayed alive, e.g. a
			// download still in quarantine waiting on macOS's "can't be opened" prompt, and the game never opened a window)
			log = Files.createTempFile("cadence-vivace-probe", ".txt");
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
			if (!p.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
				p.descendants().forEach(ProcessHandle::destroyForcibly);
				p.destroyForcibly();
				return TIMEOUT;
			}
			byte[] out;
			try (var in = Files.newInputStream(log)) {
				out = in.readNBytes(8192);
			}
			String text = new String(out, StandardCharsets.UTF_8);
			if (p.exitValue() == 0) {
				// (a newer Java warns about options it no longer has at every start; those are dropped from the restart)
				Matcher m = REMOVED.matcher(text);
				while (m.find()) removed.add(m.group(1));
				return null;
			}
			for (String line : text.split("\n")) {
				if (line.startsWith("Unrecognized VM option") || line.startsWith("Error: VM option") || line.contains("Improperly specified VM option"))
					return "Vivace refused " + line.strip();
			}
			// a signal (exit 128+n): macOS stopped it, most often a download still in quarantine
			if (p.exitValue() > 128) return STOPPED;
			return "Vivace exited with " + p.exitValue();
		} catch (IOException e) {
			return "Vivace can't be started (" + e.getMessage() + ")";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "interrupted";
		} finally {
			if (log != null) {
				try {
					Files.deleteIfExists(log);
				} catch (IOException ignored) {
				}
			}
		}
	}

	/** How long the probe waits (-Dcadence.javaProbeTimeout, seconds; tests use a short one). */
	private static final long PROBE_SECONDS = Long.getLong("cadence.javaProbeTimeout", 20);
	private static final String TIMEOUT = "Vivace didn't start in " + PROBE_SECONDS
		+ " s (macOS may be asking about it: see INSTALL.md, xattr -dr com.apple.quarantine)";
	private static final String STOPPED = "macOS stopped Vivace (see INSTALL.md: xattr -dr com.apple.quarantine)";

	/** cadence.java=PATH: a .jdk folder, a Java home, or a java program. */
	private static Path javaAt(String path) {
		Path p = Path.of(path.startsWith("~/") ? System.getProperty("user.home") + path.substring(1) : path);
		for (Path c : List.of(p, p.resolve("bin/java"), p.resolve("Contents/Home/bin/java")))
			if (Files.isRegularFile(c) && Files.isExecutable(c) && c.getFileName().toString().equals("java")) return c;
		return null;
	}

	private static boolean isVivace(Path home) {
		if (!Files.isRegularFile(home.resolve("bin/java")) || !Files.isRegularFile(home.resolve("release"))) return false;
		String impl = release(home).getProperty("IMPLEMENTOR", "");
		Path bundle = home.getParent() == null ? null : home.getParent().getParent();
		return impl.equals("Vivace") || bundle != null && bundle.getFileName().toString().startsWith("Vivace-");
	}

	/** A Java home's release file (KEY="value" lines), quotes removed. */
	private static Properties release(Path home) {
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(home.resolve("release"), StandardCharsets.UTF_8)) {
			ConfigFiles.load(p, r);
		} catch (IOException | RuntimeException ignored) {
		}
		p.replaceAll((k, v) -> v.toString().replaceAll("^\"|\"$", ""));
		return p;
	}

	private static Runtime.Version version(Properties release) {
		try {
			return Runtime.Version.parse(release.getProperty("JAVA_VERSION", "1"));
		} catch (IllegalArgumentException e) {
			// (missing, damaged or Java-8-style "1.8.0_292": oldest possible, so it is never chosen and reads as too old;
			// "0" is not a valid version and threw out of the settings screen)
			return Runtime.Version.parse("1");
		}
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p.toRealPath()).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private static Properties read(Path dir, String name, String oldName) {
		Properties p = new Properties();
		Path f = Legacy.readable(dir, name, oldName);
		if (!Files.isRegularFile(f)) return p;
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			ConfigFiles.load(p, r);
		} catch (IOException ignored) {
		}
		return p;
	}

	private static void save(Properties st, Path file) {
		try {
			ConfigFiles.store(st, file, "Cadence: Vivace start checks (pending = a start on Vivace not yet reached the game; failed = one that never did)", true);
		} catch (IOException e) {
			System.out.println("[cadence] Vivace: can't save " + file + ": " + e);
		}
	}
}
