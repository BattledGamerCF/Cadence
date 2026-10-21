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
 * Measured (docs/dev-log/DAY2.md): G1 + HeapTrim used ~870 MB less than ZGC on the same route; compact object headers ~23 MB less heap.
 * -XX:MetaspaceSize=256m: the game's class metadata grows to ~170 MB while it loads, and each threshold on the way triggered a
 * collection; the threshold only decides when to collect, it reserves nothing. Tried and withdrawn (docs/dev-log/DAY-10-18.md):
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
	 * nothing as with DisableExplicitGC before), so Cadence can start it when it is worth it. Measured (docs/dev-log/NIGHT-5.md):
	 * adaptive IHOP had fallen to 0 bytes and G1 marked back to back, each cycle ending in an 8-19 ms Remark pause (class
	 * unloading) every ~8 s while old-gen occupancy stayed flat. Only with G1 and when the player hasn't disabled System.gc().
	 */
	static final List<String> GC_POLICY = List.of("-XX:+ExplicitGCInvokesConcurrent", "-XX:-G1UseAdaptiveIHOP", "-XX:InitiatingHeapOccupancyPercent=90");
	static final List<String> DEFAULTS = List.of("-XX:+UseG1GC", "-XX:+UseCompactObjectHeaders", GC_POLICY.get(0), GC_POLICY.get(1), GC_POLICY.get(2),
		"-XX:+ParallelRefProcEnabled", "-XX:+PerfDisableSharedMem", "-XX:MetaspaceSize=256m",
		"-Dfile.encoding=UTF-8", "-Dlog4j2.formatMsgNoLookups=true").stream()
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
			summary = gc + " (Cadence default" + (given.contains("UseG1GC") ? "" : "; the collector is yours") + ")";
			System.out.println("[cadence] Java started with Cadence's defaults: " + given + " (your own Java arguments win on any conflict)");
			return;
		}
		boolean gcChosen = false;
		for (String a : input) for (String g : GCS) if (a.equals("-XX:+" + g)) gcChosen = true;
		summary = gc + (gcChosen ? " (your Java arguments)" : " (Java's default)");
		if (!enabled(gameDir) || !System.getProperty("os.name", "").startsWith("Mac")) return;
		List<String> add = new ArrayList<>(), kept = new ArrayList<>();
		// (the GC policy needs G1 and System.gc(): a player who chose another collector or -XX:+DisableExplicitGC keeps G1's own
		// marking trigger, which then has no one to start marking early)
		boolean policy = !gcChosen || input.contains("-XX:+UseG1GC");
		if (input.contains("-XX:+DisableExplicitGC")) policy = false;
		for (String d : DEFAULTS) {
			if (!policy && GC_POLICY.contains(d)) continue;
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
		if (add.stream().noneMatch(a -> a.startsWith("-XX:"))) return;
		var info = ProcessHandle.current().info();
		if (info.command().isEmpty() || info.arguments().isEmpty()) return;
		List<String> argv = new ArrayList<>();
		argv.add(info.command().get());
		argv.addAll(add);
		argv.addAll(List.of(info.arguments().get()));
		Map<String, String> env = new TreeMap<>(System.getenv());
		env.put(ENV, String.join(" ", add));
		System.out.println("[cadence] Restarting Java with Cadence's defaults: " + String.join(" ", add)
			+ (kept.isEmpty() ? "" : "; kept your own: " + String.join(" ", kept)));
		System.out.flush();
		closeOnExec();
		int r = exec(argv, env);
		System.out.println("[cadence] Java defaults: restart failed (" + r + "); carrying on as launched");
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
			if (!a.startsWith("-XX:")) continue;
			String n = flagName(a);
			if (n.equals(name) || isGc && List.of(GCS).contains(n)) return a;
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
		Path cfg = Legacy.readable(gameDir.resolve("config"), Legacy.CONFIG, Legacy.CONFIG_OLD);
		if (!Files.isRegularFile(cfg)) return true;
		Properties props = new Properties();
		try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
			dev.cadence.metal.ConfigFiles.load(props, r);
		} catch (IOException e) {
			return true;
		}
		return !"false".equalsIgnoreCase(props.getProperty("cadence.jvmDefaults", "true").strip());
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
