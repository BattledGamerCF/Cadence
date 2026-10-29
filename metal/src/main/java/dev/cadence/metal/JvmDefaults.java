package dev.cadence.metal;

import java.io.IOException;
import java.io.Reader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Cadence: the Java arguments measured best for Minecraft on an 8 GB Mac, used by default; the player's own Java arguments
 * win wherever they say something about the same setting.
 *
 * <p>The garbage collector and the other -XX options are fixed when Java starts, before any mod runs, so they can't be set
 * from inside the game. Instead, at the very start of loading (Profile.apply, first thing in the first mixin plugin, about a
 * second after launch and before Minecraft itself loads) this compares the defaults with the options Java was started with
 * (RuntimeMXBean input arguments: the command line, JDK_JAVA_OPTIONS, JAVA_TOOL_OPTIONS, argument files). If any default is
 * missing and nothing the player gave conflicts with it, the process replaces itself (execve) with the same command line, the
 * missing defaults put in front. Same process id, same output pipes: the launcher keeps tracking the game; no second Java
 * stays in memory. A conflict means the same option name, or, for the collector, any other collector chosen: then the
 * player's choice is kept and the default dropped. Nothing missing (or only -D defaults, which are set in place): no restart.
 *
 * <p>Safety: only options this Java knows are added (HotSpotDiagnosticMXBean); the restarted process carries
 * CADENCE_JVM_DEFAULTS in its environment and never restarts again; any failure just carries on as launched. The command line
 * (it holds the account token) is never logged, only the options added. Open files are marked close-on-exec first so the new
 * process doesn't inherit them. Off: -Dcadence.jvmDefaults=false, or cadence.jvmDefaults=false in config/cadence.properties.
 * The same restart can go to another Java: Vivace, when the player chose it in Video Settings (see {@link Vivace}).
 * Measured (M2 Air, same flight route): G1 + HeapTrim used ~870 MB less than ZGC on the same route; compact object headers ~23 MB less heap.
 * -XX:MetaspaceSize=256m: the game's class metadata grows to ~170 MB while it loads, and each threshold on the way triggered a
 * collection; the threshold only decides when to collect, it reserves nothing. Tried and withdrawn:
 * -XX:-ClassUnloadingWithConcurrentMark made G1's Remark pauses ~1 ms instead of up to 25 ms while flying, but G1 frees
 * replaced JIT code only while unloading classes: the code cache grew 35 -> 100 MB in 4 minutes and kept growing.
 */
public final class JvmDefaults {
	/** What the restarted process was given, from its environment ("" when it wasn't restarted). */
	private static final String ENV = "CADENCE_JVM_DEFAULTS";
	private static final String[] GCS = {"UseG1GC", "UseZGC", "UseShenandoahGC", "UseParallelGC", "UseSerialGC", "UseEpsilonGC"};
	/**
	 * Cadence's GC policy (GcPolicy in the shaders module): G1's own trigger for background marking is turned into a high safety
	 * net (fixed at 90% of the heap instead of adaptive), and System.gc() starts that marking (instead of a full collection, or
	 * nothing as with DisableExplicitGC before), so Cadence can start it when it is worth it. Measured:
	 * adaptive IHOP had fallen to 0 bytes and G1 marked back to back, each cycle ending in an 8-19 ms Remark pause (class
	 * unloading) every ~8 s while old-gen occupancy stayed flat. Only with G1 and when the player hasn't disabled System.gc().
	 */
	static final List<String> GC_POLICY = List.of("-XX:+ExplicitGCInvokesConcurrent", "-XX:-G1UseAdaptiveIHOP", ihop(Runtime.version().feature()));
	/** Java 27 renamed InitiatingHeapOccupancyPercent to G1IHOP (the old name still works there, with a warning at every start). */
	private static final String IHOP_OLD = "InitiatingHeapOccupancyPercent", IHOP_NEW = "G1IHOP";
	/**
	 * Java 26+ only: young generation at most 128 MB. From Java 26 G1 resizes the heap after every young collection by its "GC
	 * CPU usage", which now includes concurrent marking; each marking cycle (Cadence starts one every ~25 s) reads as a 20-70%
	 * spike, G1 grows the heap by hundreds of MB toward -Xmx and fills it with eden, and a young pause lasts as long as eden is
	 * big. Measured, 14-minute flights with menus (Vivace project PATCHES.md): Java 27 as is, eden ~535 MB, 193 young pauses
	 * over 8 ms, 1.1-1.5 GB resident; with this cap, 58 over 8 ms, 0.87-1.0 GB: about what Java 25 does by itself (41-51; its
	 * tighter heap keeps eden near 100 MB), and 25 with the cap only collects more often (79), so 25 doesn't get it. G1 only;
	 * the player's own young-generation sizing (-Xmn, NewSize, MaxNewSize, NewRatio, G1 new-size percents) wins.
	 */
	static final String YOUNG_CAP = "-XX:MaxNewSize=128m";
	private static final List<String> YOUNG_SIZING = List.of("MaxNewSize", "NewSize", "NewRatio", "G1NewSizePercent", "G1MaxNewSizePercent");
	static final List<String> DEFAULTS = java.util.stream.Stream.concat(Stream.of("-XX:+UseG1GC", "-XX:+UseCompactObjectHeaders",
			GC_POLICY.get(0), GC_POLICY.get(1), GC_POLICY.get(2), "-XX:+ParallelRefProcEnabled", "-XX:+PerfDisableSharedMem",
			"-XX:MetaspaceSize=256m", "-Dfile.encoding=UTF-8", "-Dlog4j2.formatMsgNoLookups=true"),
			Runtime.version().feature() >= 26 ? Stream.of(YOUNG_CAP) : Stream.empty())
		// (compact object headers are a product flag from Java 25, which the game needs; on anything older the restarted
		// Java would refuse to start, so it is left out)
		.filter(d -> Runtime.version().feature() >= 25 || !d.contains("CompactObjectHeaders")).toList();
	private static boolean ran;
	private static String summary = "";

	private JvmDefaults() {
	}

	/** One line for the settings screen: which collector runs, and whose choice it was. */
	public static String summary() {
		return summary;
	}

	static synchronized void apply(Path gameDir) {
		if (ran) return;
		ran = true;
		try {
			run(gameDir);
		} catch (Throwable t) {
			System.out.println("[cadence] Java defaults: skipped (" + t + ")");
		}
	}

	private static void run(Path gameDir) throws Throwable {
		List<String> input = ManagementFactory.getRuntimeMXBean().getInputArguments();
		String gc = ManagementFactory.getGarbageCollectorMXBeans().isEmpty() ? "?"
			: ManagementFactory.getGarbageCollectorMXBeans().get(0).getName().split(" ")[0];
		String given = System.getenv(ENV);
		if (given != null) {
			GameMode.watchParent();
			summary = gc + (given.contains("UseG1GC") ? " (Cadence default)" : given.isEmpty() ? "" : " (Cadence default; the collector is yours)");
			Vivace.reached(gameDir);
			System.out.println("[cadence] Java started" + (Vivace.running() ? " on Vivace" : "") + " with Cadence's defaults: " + given
				+ " (your own Java arguments win on any conflict)");
			return;
		}
		boolean gcChosen = false;
		for (String a : input) for (String g : GCS) if (a.equals("-XX:+" + g)) gcChosen = true;
		summary = gc + (gcChosen ? " (your Java arguments)" : " (Java's default)");
		if (!System.getProperty("os.name", "").startsWith("Mac")) return;
		boolean defaults = enabled(gameDir);
		List<String> add = new ArrayList<>(), kept = new ArrayList<>();
		// (the GC policy needs G1 and System.gc(): a player who chose another collector or -XX:+DisableExplicitGC keeps G1's own
		// marking trigger, which then has no one to start marking early)
		boolean policy = !gcChosen || input.contains("-XX:+UseG1GC");
		if (input.contains("-XX:+DisableExplicitGC")) policy = false;
		boolean g1 = !gcChosen || input.contains("-XX:+UseG1GC");
		for (String d : defaults ? DEFAULTS : List.<String>of()) {
			if (!policy && GC_POLICY.contains(d)) continue;
			if (!g1 && d.equals(YOUNG_CAP)) continue;
			String conflict = conflict(d, input);
			if (conflict != null) {
				if (!conflict.equals(d)) kept.add(conflict);
				continue;
			}
			if (d.startsWith("-D")) {
				// a property can be set now: no restart for it (it rides along if there is one)
				int eq = d.indexOf('=');
				if (System.getProperty(d.substring(2, eq)) == null) System.setProperty(d.substring(2, eq), d.substring(eq + 1));
				add.add(d);
				continue;
			}
			if (!known(d)) continue;
			add.add(d);
		}
		var info = ProcessHandle.current().info();
		if (info.command().isEmpty() || info.arguments().isEmpty()) return;
		// (the player's choice, Video Settings > Java: the restart goes to Vivace's java instead of this one)
		Path vivace = Vivace.target(gameDir, input, add);
		if (vivace != null) {
			// (defaults that depend on the Java version follow the Java the game restarts on)
			int feature = Vivace.feature(vivace);
			add.replaceAll(a -> a.equals(ihop(Runtime.version().feature())) ? ihop(feature) : a);
			add.remove(YOUNG_CAP);
			if (defaults && g1 && feature >= 26 && conflict(YOUNG_CAP, input) == null) add.add(YOUNG_CAP);
			add.removeIf(a -> a.startsWith("-XX:") && Vivace.removedOptions().contains(flagName(a)));
		}
		// (Game Mode: the game is started again from a bundle macOS treats as a game; that alone is reason enough to restart)
		boolean gameMode = GameMode.enabled(config(gameDir));
		if (vivace == null && !gameMode && add.stream().noneMatch(a -> a.startsWith("-XX:"))) return;
		List<String> argv = new ArrayList<>();
		argv.add(vivace != null ? vivace.toString() : info.command().get());
		argv.addAll(add);
		argv.addAll(List.of(info.arguments().get()));
		Map<String, String> env = new TreeMap<>(System.getenv());
		env.put(ENV, String.join(" ", add));
		if (vivace != null) env.put(Vivace.ENV, vivace.toString());
		else if (!Vivace.note().isEmpty()) env.put(Vivace.NOTE_ENV, Vivace.note());
		System.out.println("[cadence] Restarting Java" + (vivace != null ? " on Vivace (" + vivace + ")" : "")
			+ (add.isEmpty() ? "" : " with Cadence's defaults: " + String.join(" ", add))
			+ (kept.isEmpty() ? "" : "; kept your own: " + String.join(" ", kept)));
		System.out.flush();
		List<String> waiter = gameMode ? GameMode.spawn(Path.of(argv.get(0)), argv, env) : null;
		if (waiter != null) {
			System.out.println("[cadence] Started as a game (macOS Game Mode); this process waits for it");
			System.out.flush();
			closeOnExec();
			int w = exec(waiter, new TreeMap<>(System.getenv()));
			// (the exec failed: the game is already running, so this one must not start a second; it waits for it instead)
			System.out.println("[cadence] Game Mode: the wait failed (" + w + "); waiting in Java");
			ProcessHandle.of(Long.parseLong(waiter.get(2))).ifPresent(h -> h.onExit().join());
			Runtime.getRuntime().halt(0);
		}
		closeOnExec();
		int r = exec(argv, env);
		if (vivace != null) Vivace.execFailed(gameDir);
		System.out.println("[cadence] Java defaults: restart failed (" + r + "); carrying on as launched");
	}

	/** The IHOP default under the name a Java of that version knows. */
	static String ihop(int feature) {
		return "-XX:" + (feature >= 27 ? IHOP_NEW : IHOP_OLD) + "=90";
	}

	/** The player's option that decides the same setting as {@code d}, or null. */
	private static String conflict(String d, List<String> input) {
		if (d.startsWith("-D")) {
			String key = d.substring(0, d.indexOf('=') + 1);
			for (String a : input) if (a.startsWith(key)) return a;
			return null;
		}
		String name = flagName(d);
		boolean isGc = List.of(GCS).contains(name);
		for (String a : input) {
			if (name.equals("MaxNewSize") && a.startsWith("-Xmn")) return a;
			if (!a.startsWith("-XX:")) continue;
			String n = flagName(a);
			if (n.equals(name) || isGc && List.of(GCS).contains(n)) return a;
			if (List.of(IHOP_OLD, IHOP_NEW).contains(name) && List.of(IHOP_OLD, IHOP_NEW).contains(n)) return a;
			if (name.equals("MaxNewSize") && YOUNG_SIZING.contains(n)) return a;
		}
		return null;
	}

	/** -XX:+Name, -XX:-Name, -XX:Name=value -> Name. */
	private static String flagName(String a) {
		String s = a.substring(4);
		if (s.startsWith("+") || s.startsWith("-")) s = s.substring(1);
		int eq = s.indexOf('=');
		return eq < 0 ? s : s.substring(0, eq);
	}

	private static boolean known(String d) {
		try {
			ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).getVMOption(flagName(d));
			return true;
		} catch (IllegalArgumentException unknown) {
			return false;
		}
	}

	private static boolean enabled(Path gameDir) {
		Legacy.migrateProperties();
		String p = System.getProperty("cadence.jvmDefaults");
		if (p != null) return !"false".equalsIgnoreCase(p.strip());
		return !"false".equalsIgnoreCase(config(gameDir).getProperty("cadence.jvmDefaults", "true").strip());
	}

	/** config/cadence.properties (empty when there is none or it can't be read). */
	private static Properties config(Path gameDir) {
		Properties props = new Properties();
		Path cfg = Legacy.readable(gameDir.resolve("config"), Legacy.CONFIG, Legacy.CONFIG_OLD);
		if (!Files.isRegularFile(cfg)) return props;
		try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
			dev.cadence.metal.ConfigFiles.load(props, r);
		} catch (IOException e) {
			// (as if there were none)
		}
		return props;
	}

	/** Every open file but stdin/out/err closes at the exec (the other threads keep using theirs until then). */
	private static void closeOnExec() throws Throwable {
		Linker linker = Linker.nativeLinker();
		MethodHandle fcntl = linker.downcallHandle(linker.defaultLookup().find("fcntl").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
			Linker.Option.firstVariadicArg(2));
		List<Integer> fds = new ArrayList<>();
		try (Stream<Path> s = Files.list(Path.of("/dev/fd"))) {
			s.forEach(p -> {
				try {
					fds.add(Integer.parseInt(p.getFileName().toString()));
				} catch (NumberFormatException ignored) {
				}
			});
		}
		for (int fd : fds) if (fd > 2) {
			int ignored = (int) fcntl.invokeExact(fd, 2 /* F_SETFD */, 1 /* FD_CLOEXEC */);
		}
	}

	private static int exec(List<String> argv, Map<String, String> env) throws Throwable {
		Linker linker = Linker.nativeLinker();
		MethodHandle execve = linker.downcallHandle(linker.defaultLookup().find("execve").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment av = strings(arena, argv);
			List<String> e = new ArrayList<>();
			env.forEach((k, v) -> e.add(k + "=" + v));
			MemorySegment ev = strings(arena, e);
			return (int) execve.invokeExact(arena.allocateFrom(argv.get(0)), av, ev);
		}
	}

	private static MemorySegment strings(Arena arena, List<String> list) {
		MemorySegment array = arena.allocate(ValueLayout.ADDRESS, list.size() + 1);
		for (int i = 0; i < list.size(); i++) array.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(list.get(i)));
		array.setAtIndex(ValueLayout.ADDRESS, list.size(), MemorySegment.NULL);
		return array;
	}
}
