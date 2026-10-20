package dev.cadence.shaders.cadence.benchmark;

import dev.cadence.metal.MacSystem;
import dev.cadence.shaders.cadence.CadenceSettings;
import dev.cadence.shaders.cadence.RenderScale;
import dev.cadence.shaders.cadence.ShaderToggle;
import dev.cadence.shaders.cadence.setup.QuickSetup;
import dev.cadence.shaders.cadence.system.BackgroundFps;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cadence Benchmark: 30 seconds of the same view turning once all the way round, measured frame by frame, so a player (or two
 * Macs, or two settings on one Mac) can compare how smooth the game really is.
 *
 * <p>The run is driven from the render thread: {@link #frame()} is called once per frame (BenchmarkFrameMixin, just before the
 * camera is set up). Each frame it checks the run is still sane (a world, no screen, Minecraft in front), sets the player's yaw
 * from the elapsed time (a constant speed, so every run shows the same views in the same order), and, after a 2 second warm-up,
 * records the interval since the previous frame. GPU time comes from {@link dev.cadence.metal.GpuBusy} (the same figure Auto
 * resolution uses; it works with or without a shader pack) and the render thread's CPU time from the JVM, both as totals over
 * the measured part divided by its frame count.
 *
 * <p>Whatever ends the run (it finishing, Escape or another screen, the world closing, an error), the player's yaw and pitch and
 * the HUD are put back first. Nothing is sent anywhere; the results are only logged, kept in Cadence's config (the last one, for
 * comparison) and written to the screenshots folder when the run finishes.
 */
public final class Benchmark {
	private static final Logger LOGGER = LoggerFactory.getLogger("cadence");
	/** The whole run, and the start of it that is not measured (the first frames after a screen closes are slow). */
	static final long DURATION_NS = 30_000_000_000L, WARMUP_NS = 2_000_000_000L;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);

	private Benchmark() {
	}

	/** What a finished run measured (gpuMs and cpuMs are -1 when unknown). setup: "Label|value" lines. */
	public record Result(double avgFps, double lowFps, double worstMs, double gpuMs, double cpuMs, int frames, double seconds,
		List<String> setup, String stamp) {
		/** The one-line form for the log and the self-test. */
		public String line() {
			return String.format(Locale.ROOT, "avg %.0f fps, 1%% low %.0f fps, worst %.1f ms, gpu %s, cpu %s (%d frames in %.1f s)", avgFps, lowFps,
				worstMs, gpuMs < 0 ? "unknown" : String.format(Locale.ROOT, "%.2f ms", gpuMs),
				cpuMs < 0 ? "unknown" : String.format(Locale.ROOT, "%.2f ms", cpuMs), frames, seconds);
		}
	}

	// ---- the run's state (render thread; running is read from the UI too) ----
	private static volatile boolean running;
	private static Consumer<Result> onDone;
	private static Consumer<String> onStopped;
	private static Player player;
	private static float startYaw, startPitch;
	private static boolean hudHiddenByUs;
	private static long startedAt, lastFrame, measureStart, gpuStart, cpuStart;
	private static boolean measuring;
	private static float[] intervals = new float[8192];
	private static int count;
	private static Thread renderThread;
	/** (self-test) the focus check is skipped: an automated run's window is usually not in front. */
	public static boolean ignoreFocus = System.getProperty("cadence.selftest") != null;

	public static boolean running() {
		return running;
	}

	/** Why a run can't start right now, or null: a world must be showing. */
	public static String cannotStart() {
		Minecraft mc = Minecraft.getInstance();
		if (running) return "A benchmark is already running.";
		if (mc.level == null || mc.player == null) return "Open a world first: the benchmark turns your view in a world.";
		if (QuickSetup.testing) return "Quick Setup is testing your Mac.";
		return null;
	}

	/**
	 * Starts a run (render thread): screens closed, HUD hidden, the player's view remembered. One of the callbacks is called
	 * later, on the render thread, with the HUD and view already restored. onStopped gets the reason in plain words.
	 */
	public static void start(Consumer<Result> done, Consumer<String> stopped) {
		Minecraft mc = Minecraft.getInstance();
		String why = cannotStart();
		if (why != null) {
			stopped.accept(why);
			return;
		}
		onDone = done;
		onStopped = stopped;
		player = mc.player;
		startYaw = player.getYRot();
		startPitch = player.getXRot();
		hudHiddenByUs = !mc.gui.hud.isHidden();
		if (hudHiddenByUs) mc.gui.hud.toggle();
		startedAt = 0;
		lastFrame = 0;
		measuring = false;
		count = 0;
		renderThread = Thread.currentThread();
		running = true;
		mc.gui.setScreen(null);
		LOGGER.info("Benchmark: started");
	}

	/** Stops a run from outside the frame loop (a closing world, the self-test): everything restored. */
	public static void cancel(String reason) {
		if (running) stop(reason);
	}

	/** Once per rendered frame, on the render thread. */
	public static void frame() {
		if (!running) return;
		try {
			Minecraft mc = Minecraft.getInstance();
			long now = System.nanoTime();
			String problem = problem(mc);
			if (problem != null) {
				stop(problem);
				return;
			}
			if (startedAt == 0) startedAt = now;
			long elapsed = now - startedAt;
			if (elapsed >= DURATION_NS) {
				finish(mc, now);
				return;
			}
			KeyMapping.releaseAll();  // (keys and mouse buttons the player presses do nothing: the view is Cadence's for 30 s)
			float yaw = startYaw + 360f * elapsed / DURATION_NS;
			player.setYRot(yaw);
			player.yRotO = yaw;
			player.yHeadRot = yaw;
			player.yHeadRotO = yaw;
			player.setXRot(startPitch);
			player.xRotO = startPitch;
			if (elapsed >= WARMUP_NS) {
				if (!measuring) {
					measuring = true;
					measureStart = now;
					gpuStart = gpuTotal();
					cpuStart = cpuTotal();
				} else add((now - lastFrame) / 1e6f);
			}
			lastFrame = now;
		} catch (Throwable t) {
			LOGGER.warn("Benchmark: stopped by an error", t);
			stop("Something went wrong (see the log).");
		}
	}

	private static String problem(Minecraft mc) {
		if (mc.level == null || mc.player == null || mc.player != player) return "The world was closed.";
		if (mc.gui.screen() instanceof PauseScreen) return "Stopped: Escape was pressed.";
		if (mc.gui.screen() != null) return "Stopped: a screen opened.";
		if (!ignoreFocus && (BackgroundFps.fakeBackground || !mc.getWindow().isFocused())) return "Stopped: Minecraft was not in front.";
		return null;
	}

	private static void add(float ms) {
		if (count == intervals.length) intervals = Arrays.copyOf(intervals, count * 2);
		intervals[count++] = ms;
	}

	private static long gpuTotal() {
		try {
			return dev.cadence.metal.GpuBusy.totalNanos();
		} catch (LinkageError e) {
			return 0;  // (not on Cadence's Metal backend)
		}
	}

	private static long cpuTotal() {
		try {
			return ManagementFactory.getThreadMXBean().getThreadCpuTime(renderThread.threadId());
		} catch (RuntimeException e) {
			return -1;
		}
	}

	private static void finish(Minecraft mc, long now) {
		if (!measuring || count < 10) {
			stop("Too few frames were drawn to measure.");
			return;
		}
		add((now - lastFrame) / 1e6f);
		long gpuEnd = gpuTotal(), cpuEnd = cpuTotal();
		int n = count;
		double seconds = (now - measureStart) / 1e9;
		float[] sorted = Arrays.copyOf(intervals, n);
		Arrays.sort(sorted);
		double p99 = sorted[Math.min(n - 1, (int) (n * 0.99))];
		double gpu = gpuEnd > gpuStart ? (gpuEnd - gpuStart) / 1e6 / n : -1;
		double cpu = cpuStart >= 0 && cpuEnd > cpuStart ? (cpuEnd - cpuStart) / 1e6 / n : -1;
		Result r = new Result(n / seconds, 1000.0 / p99, sorted[n - 1], gpu, cpu, n, seconds, setupLines(mc), LocalDateTime.now().format(STAMP));
		Consumer<Result> done = onDone;
		restore(mc);
		LOGGER.info("Benchmark: {}", r.line());
		writeText(mc, r);
		done.accept(r);
	}

	private static void stop(String reason) {
		Minecraft mc = Minecraft.getInstance();
		Consumer<String> stopped = onStopped;
		restore(mc);
		LOGGER.info("Benchmark: stopped ({})", reason);
		if (stopped != null) stopped.accept(reason);
	}

	/** The player's view and the HUD back as they were (safe to call twice). */
	private static void restore(Minecraft mc) {
		running = false;
		try {
			if (player != null && mc.player == player) {
				player.setYRot(startYaw);
				player.yRotO = startYaw;
				player.yHeadRot = startYaw;
				player.yHeadRotO = startYaw;
				player.setXRot(startPitch);
				player.xRotO = startPitch;
			}
			if (hudHiddenByUs && mc.gui.hud.isHidden()) mc.gui.hud.toggle();
		} catch (RuntimeException e) {
			LOGGER.warn("Benchmark: restoring the view failed", e);
		}
		hudHiddenByUs = false;
		player = null;
		onDone = null;
		onStopped = null;
	}

	// ---- the setup, the last result, the text file ----

	/** What the numbers were measured on, one plain "Label|value" line each (for the card and the text file). */
	static List<String> setupLines(Minecraft mc) {
		List<String> out = new ArrayList<>();
		String model = MacSystem.productName();
		if (model.isEmpty()) model = MacSystem.sysString("hw.model");
		String chip = MacSystem.sysString("machdep.cpu.brand_string");
		out.add("Mac|" + (model.isEmpty() ? "Unknown Mac" : model) + (chip.isEmpty() ? "" : " · " + chip));
		long mem = MacSystem.sysLong("hw.memsize");
		out.add("Memory|" + (mem > 0 ? Math.round(mem / 1073741824.0) + " GB" : "unknown") + " · macOS " + System.getProperty("os.version", "?"));
		out.add("Window|" + mc.getWindow().getWidth() + " × " + mc.getWindow().getHeight() + " pixels · render distance "
			+ mc.options.renderDistance().get() + " chunks");
		String pack = ShaderToggle.selectedPack();
		out.add("Shader pack|" + (pack.isEmpty() ? "None" : ShaderToggle.display(pack)));
		out.add("Frame cap|" + frameCap(mc));
		if (dev.cadence.shaders.cadence.MaxFpsMode.on()) out.add("World|not drawn (Render Max FPS)");
		out.add("Cadence|" + version() + " · " + CadenceSettings.current().label() + " · world at " + Math.round(RenderScale.get() * 100) + "%");
		return out;
	}

	/** The frame-rate cap the run had, and why (so a capped run isn't taken for the Mac's speed): "None" without one. */
	private static String frameCap(Minecraft mc) {
		int option = mc.options.framerateLimit().get();
		int battery = dev.cadence.shaders.cadence.system.BatteryMode.fpsCap(), heat = dev.cadence.shaders.cadence.system.HeatControl.fpsCap();
		int cap = Math.min(option >= net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF ? Integer.MAX_VALUE : option, Math.min(battery, heat));
		if (cap == Integer.MAX_VALUE) return "None";
		String why = cap == battery ? "On Battery" : cap == heat ? "Cooling" : "Max Framerate";
		return cap + " fps (" + why + ")";
	}

	private static String version() {
		return FabricLoader.getInstance().getModContainer("cadence").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
	}

	/** Remembers this result as "the last one" (Cadence's config) for the next card's comparison. */
	static void remember(Result r) {
		try {
			ShaderToggle.saveKey("benchmarkLast", String.format(Locale.ROOT, "%.1f|%.1f", r.avgFps(), r.lowFps()));
		} catch (RuntimeException e) {
			// (the comparison is a nicety)
		}
	}

	/** The remembered run's {average, 1% low} fps, or null. */
	static double[] previous() {
		String[] p = ShaderToggle.configValue("benchmarkLast", "").split("\\|");
		try {
			return p.length == 2 ? new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1])} : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** screenshots/cadence-benchmark-DATE.txt: the same figures as the card, for pasting into a forum post. */
	private static void writeText(Minecraft mc, Result r) {
		StringBuilder sb = new StringBuilder("Cadence benchmark, ").append(r.stamp().replace('_', ' ').replace('.', ':')).append("\n\n");
		if (CadenceSettings.benchmarkDetailed()) sb.append(String.format(Locale.ROOT, "Average: %.0f fps%n1%% low: %.0f fps%nWorst frame: %.1f ms%nGraphics (GPU) time per frame: %s%n"
			+ "Processor (render thread) time per frame: %s%nFrames measured: %d in %.1f s%n%n", r.avgFps(), r.lowFps(), r.worstMs(),
			r.gpuMs() < 0 ? "unknown" : String.format(Locale.ROOT, "%.2f ms", r.gpuMs()),
			r.cpuMs() < 0 ? "unknown" : String.format(Locale.ROOT, "%.2f ms", r.cpuMs()), r.frames(), r.seconds()));
		else sb.append(String.format(Locale.ROOT, "Average: %.0f fps%n%n", r.avgFps()));
		for (String line : r.setup()) sb.append(line.replace("|", ": ")).append('\n');
		try {
			Path dir = mc.gameDirectory.toPath().resolve("screenshots");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(fileStem(r) + ".txt"), sb.toString(), StandardCharsets.UTF_8);
		} catch (java.io.IOException | RuntimeException e) {
			LOGGER.warn("Benchmark: can't write the text file: {}", e.toString());
		}
	}

	static String fileStem(Result r) {
		return "cadence-benchmark-" + r.stamp();
	}
}
