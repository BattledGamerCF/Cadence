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
