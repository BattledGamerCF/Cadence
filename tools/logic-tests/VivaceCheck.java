package dev.cadence.metal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Offline test of Vivace (choosing and checking the Java Cadence restarts the game on), against fake Java folders and a
 * real Java given as the first argument (a Java home that accepts the usual -XX options). No game started.
 */
public class VivaceCheck {
	static int failed;

	public static void main(String[] a) throws Exception {
		Path realHome = Path.of(a[0]);
		Path tmp = Files.createTempDirectory("vivace-check");
		try {
			// installed(): the newest Vivace in the user's folder; the full JDK before the runtime at the same version; other
			// vendors and folders without a java ignored
			Path jvms = tmp.resolve("home/Library/Java/JavaVirtualMachines");
			fake(jvms.resolve("zulu-27.jdk"), "27.0.2", "Azul Systems, Inc.", false);
			fake(jvms.resolve("Vivace-25.0.5+8-vivace.1-runtime.jdk"), "25.0.5", null, false);
			fake(jvms.resolve("Vivace-25.0.5+8-vivace.1.jdk"), "25.0.5", "Vivace", true);
			Files.createDirectories(jvms.resolve("Vivace-broken.jdk/Contents/Home"));
			String home = System.getProperty("user.home");
			System.setProperty("user.home", tmp.resolve("home").toString());
			check("installed: full JDK before the runtime", Vivace.installed(), jvms.resolve("Vivace-25.0.5+8-vivace.1.jdk/Contents/Home/bin/java"));
			fake(jvms.resolve("Vivace-27.0.1+9-vivace.1-runtime.jdk"), "27.0.1", "Vivace", false);
			check("installed: newest version first", Vivace.installed(), jvms.resolve("Vivace-27.0.1+9-vivace.1-runtime.jdk/Contents/Home/bin/java"));
			Path support = tmp.resolve("home/Library/Application Support/Vivace");
			fake(support.resolve("Vivace-27.0.2+1-vivace.1-runtime.jdk"), "27.0.2", "Vivace", false);
			check("installed: Application Support/Vivace too", Vivace.installed(), support.resolve("Vivace-27.0.2+1-vivace.1-runtime.jdk/Contents/Home/bin/java"));
			System.setProperty("user.home", home);

			// target(): the setting, the probe, the pending/failed notes
			Path game = tmp.resolve("game");
			Files.createDirectories(game.resolve("config"));
			List<String> add = List.of("-XX:+UseG1GC", "-XX:InitiatingHeapOccupancyPercent=90");
			System.setProperty("cadence.java", "launcher");
			check("setting launcher: stay", Vivace.target(game, List.of(), add), null);
			System.setProperty("cadence.java", realHome.toString());
			Path java = realHome.resolve("bin/java");
			check("a path: restart on it", Vivace.target(game, List.of("-Xmx2G", "-XX:+AlwaysPreTouch"), add), java);
			check("a start that never got here: not again", Vivace.target(game, List.of(), add), null);
			checkNote("...and says so", "didn't start last time");
			Vivace.clearFailure(game);
			check("turned on again: retried", Vivace.target(game, List.of(), add), java);
			Vivace.execFailed(game);  // (the exec failed: pending cleared, so the next start tries again)
			check("after a failed exec: tried again", Vivace.target(game, List.of(), add), java);
			Vivace.execFailed(game);
			check("an option it refuses: stay", Vivace.target(game, List.of("-XX:+NoSuchOptionAnywhere"), add), null);
			checkNote("...and names it", "NoSuchOptionAnywhere");
			if (a.length > 1) {
				// a Java 27 (second argument): an option it removed is reported by the probe and left out of the restart
				System.setProperty("cadence.java", a[1]);
				check("Java 27: restart on it", Vivace.target(game, List.of(), List.of("-XX:+ParallelRefProcEnabled")), Path.of(a[1], "bin/java"));
				Vivace.execFailed(game);
				check("...and ParallelRefProcEnabled is known to be gone", Vivace.removedOptions().contains("ParallelRefProcEnabled"), true);
			}
			// a java that never exits (a quarantined download waiting on macOS's prompt): the probe gives up, and that Vivace is
			// not probed again (no wait at every launch) until it changes or the switch is turned on again
			Path hang = tmp.resolve("hang/Contents/Home");
			Files.createDirectories(hang.resolve("bin"));
			Files.writeString(hang.resolve("release"), "JAVA_VERSION=\"25.0.5\"\nIMPLEMENTOR=\"Vivace\"\n");
			Files.writeString(hang.resolve("bin/java"), "#!/bin/bash\nsleep 300\n");
			hang.resolve("bin/java").toFile().setExecutable(true);
			System.setProperty("cadence.java", hang.toString());
			long t0 = System.nanoTime();
			check("a java that hangs: stay", Vivace.target(game, List.of(), add), null);
			long ms = (System.nanoTime() - t0) / 1_000_000;
			check("...within the timeout (" + ms + " ms)", ms < 5_000, true);
			checkNote("...and says why", "didn't start in");
			check("...and isn't probed again", Vivace.target(game, List.of(), add), null);
			checkNote("...(said so)", "didn't start last time");
			// a release file with an unparsable version: never chosen, never a crash (two candidates, so the comparator runs)
			String home2 = System.getProperty("user.home");
			System.setProperty("user.home", tmp.resolve("home2").toString());
			Path j2 = tmp.resolve("home2/Library/Java/JavaVirtualMachines");
			fake(j2.resolve("Vivace-25.0.5+8-vivace.1.jdk"), "25.0.5", "Vivace", false);
			fake(j2.resolve("Vivace-broken.jdk"), "banana", "Vivace", false);
			Path chosen = null;
			try {
				chosen = Vivace.installed();
			} catch (RuntimeException e) {
				check("garbage version: no exception (" + e + ")", false, true);
			}
			check("garbage version: the valid one chosen", chosen, j2.resolve("Vivace-25.0.5+8-vivace.1.jdk/Contents/Home/bin/java"));
			System.setProperty("user.home", home2);
			System.setProperty("cadence.java", j2.resolve("Vivace-broken.jdk").toString());
			check("garbage version via cadence.java: stay, no exception", Vivace.target(game, List.of(), add), null);
			checkNote("...too old", "too old");
			// where the setting comes from (the Video Settings button is greyed when it's not its own)
			check("source: -D", Vivace.settingSource(game), "-Dcadence.java in your Java arguments");
			System.setProperty("cadence.java", tmp.resolve("nothing-here").toString());
			check("a path with no Java: stay", Vivace.target(game, List.of(), add), null);
			// the button's setting (cadence-shaders.properties java=) when nothing else says
			System.clearProperty("cadence.java");
			Files.writeString(game.resolve("config/cadence-shaders.properties"), "java=launcher\n");
			check("button setting read", Vivace.setting(game), "launcher");
			Files.writeString(game.resolve("config/cadence.properties"), "cadence.java=vivace\n");
			check("cadence.properties wins over the button", Vivace.setting(game), "vivace");
		} finally {
			try (var s = Files.walk(tmp)) {
				s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
		System.out.println(failed == 0 ? "VivaceCheck: all passed" : "VivaceCheck: " + failed + " FAILED");
		if (failed > 0) System.exit(1);
	}

	static void fake(Path bundle, String version, String implementor, boolean jdk) throws Exception {
		Path h = bundle.resolve("Contents/Home");
		Files.createDirectories(h.resolve("bin"));
		Files.writeString(h.resolve("bin/java"), "");
		h.resolve("bin/java").toFile().setExecutable(true);
		if (jdk) Files.writeString(h.resolve("bin/javac"), "");
		Files.writeString(h.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n" + (implementor == null ? "" : "IMPLEMENTOR=\"" + implementor + "\"\n"));
	}

	static void check(String what, Object got, Object want) {
		boolean ok = java.util.Objects.equals(got, want);
		if (!ok) failed++;
		System.out.println((ok ? "  ok   " : "  FAIL ") + what + (ok ? "" : ": got " + got + ", want " + want));
	}

	static void checkNote(String what, String part) {
		check(what + " (" + Vivace.note() + ")", Vivace.note().contains(part), true);
	}
}
