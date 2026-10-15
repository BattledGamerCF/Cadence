package dev.cadence.shaders.cadence.setup;

import dev.cadence.metal.MacSystem;
import dev.cadence.shaders.cadence.CadenceSettings;
import dev.cadence.shaders.cadence.ShaderToggle;
import dev.cadence.shaders.cadence.mesh.SmartLeaves;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

/**
 * Cadence Quick Setup: picks settings for this particular Mac in about 15 seconds, once.
 *
 * <p>What the Mac is comes from macOS (the product name "MacBook Air (M2, 2022)", the chip, its CPU and GPU core counts,
 * memory; "MacBook Air" means no fan). A built-in table gives the GPU strength to expect from the chip generation and GPU
 * core count. Then a short synthetic test, the same on every Mac: a fixed terrain-like scene and a fixed shader-pack-like
 * chain drawn offscreen at 1920x1080 (so the display doesn't matter), and a fixed CPU workload on every core, each the
 * median of three runs. The measured strength is kept within 1.5x of the table's (one tier), and only the tier is used, so
 * identical Macs land on identical settings; a warm Mac or Low Power Mode skips the test and uses the table.
 *
 * <p>Scores are relative to a MacBook Air M2 with the 8-core GPU (1.0).
 */
public final class QuickSetup {
	/** The reference Mac's results (MacBook Air M2, 8-core GPU, 8 GB; 2026-10-14). */
	private static final double REF_SCENE_MS = 5.80, REF_CHAIN_MS = 13.42, REF_CPU_MS = 120.0;
	private static final Pattern CHIP = Pattern.compile("M(\\d+)(?:\\s+(Pro|Max|Ultra))?", Pattern.CASE_INSENSITIVE);

	/** While the test runs the game draws at most 10 fps (FramerateCapMixin), so the test has the Mac to itself. */
	public static volatile boolean testing;

	private QuickSetup() {
	}

	/** This Mac, as macOS describes it. */
	public record Mac(String product, String chip, int generation, String variant, int pCores, int eCores, int memoryGB, int gpuCores,
		boolean fanless) {
		public String describe() {
			String name = product.isEmpty() ? chip : product;
			return name + " · " + memoryGB + " GB" + (fanless ? " · no fan" : "");
		}
	}

	public static Mac detect() {
		String product = MacSystem.productName();
		String chip = MacSystem.sysString("machdep.cpu.brand_string");
		Matcher m = CHIP.matcher(chip);
		int gen = 0;
		String variant = "";
		if (m.find()) {
			gen = Integer.parseInt(m.group(1));
			variant = m.group(2) == null ? "" : m.group(2).substring(0, 1).toUpperCase(Locale.ROOT) + m.group(2).substring(1).toLowerCase(Locale.ROOT);
		}
		int p = (int) MacSystem.sysLong("hw.perflevel0.physicalcpu"), e = (int) MacSystem.sysLong("hw.perflevel1.physicalcpu");
		long mem = MacSystem.sysLong("hw.memsize");
		int gpu = MacSystem.gpuCores();
		if (gpu <= 0) gpu = switch (variant) {
			case "Pro" -> 16;
			case "Max" -> 32;
			case "Ultra" -> 64;
			default -> 8;
		};
		boolean fanless = product.startsWith("MacBook Air");
		return new Mac(product, chip, gen, variant, Math.max(p, 0), Math.max(e, 0), (int) Math.round(mem / 1073741824.0), gpu, fanless);
	}

	/** The GPU strength the table expects (per-core speed by generation, estimates; M2 = 1.0 per 8 cores). */
	static double expectedGpu(Mac mac) {
		double perCore = switch (mac.generation()) {
			case 1 -> 0.84;
			case 2 -> 1.0;
			case 3 -> 1.2;
			case 4 -> 1.4;
			case 5 -> 1.7;
			case 6 -> 2.0;
			default -> mac.generation() > 6 ? 2.0 + 0.3 * (mac.generation() - 6) : 1.0;
		};
		return mac.gpuCores() / 8.0 * perCore;
	}

	/** 1 entry (M1-class base), 2 base chips, 3 Pro-class, 4 Max-class, 5 Ultra-class. */
	static int tier(double gpu) {
		return gpu < 0.85 ? 1 : gpu < 1.6 ? 2 : gpu < 3 ? 3 : gpu < 6 ? 4 : 5;
	}

	/** One recommended change: what, from what, to what, and why (a few words). */
	public record Change(String what, String from, String to, String why, Runnable apply) {
	}

	public record Result(Mac mac, boolean measured, double gpuScore, double cpuScore, int tier, List<Change> changes) {
	}

	/** The test and the recommendations (any thread; the GPU part has its own device and queue). progress: a short stage name. */
	public static Result run(java.util.function.Consumer<String> progress) {
		Mac mac = detect();
		double expected = expectedGpu(mac);
		boolean warm = !Boolean.getBoolean("cadence.setupForce") && (MacSystem.thermalState() >= 1 || MacSystem.lowPowerMode());
		double gpu = expected, cpu = 1.0;
		boolean measured = false;
		if (!warm) try {
			testing = true;
			Thread.sleep(500);  // the frames already queued finish
			progress.accept("Checking graphics...");
			double[] g = MacSystem.benchGpu();
			if (g != null && g[0] > 0 && g[1] > 0) {
				double raw = Math.sqrt(REF_SCENE_MS / g[0] * (REF_CHAIN_MS / g[1]));
				gpu = Math.max(expected / 1.5, Math.min(expected * 1.5, raw));
				measured = true;
				LoggerFactory.getLogger("cadence").info("Quick Setup: GPU scene {} ms, chain {} ms -> {} (table {})", String.format(Locale.ROOT, "%.2f", g[0]),
					String.format(Locale.ROOT, "%.2f", g[1]), String.format(Locale.ROOT, "%.2f", raw), String.format(Locale.ROOT, "%.2f", expected));
			}
			progress.accept("Checking processor...");
			double ms = cpuTest();
			if (ms > 0) cpu = REF_CPU_MS / ms;
			LoggerFactory.getLogger("cadence").info("Quick Setup: CPU {} ms -> {}", String.format(Locale.ROOT, "%.1f", ms), String.format(Locale.ROOT, "%.2f", cpu));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			testing = false;
		}
		int tier = tier(gpu);
		Result r = new Result(mac, measured, gpu, cpu, tier, recommend(mac, tier, cpu));
		LoggerFactory.getLogger("cadence").info("Quick Setup: {} (chip {} {}, {}P+{}E CPU, {} GPU cores) -> tier {}, {} change(s)", mac.describe(),
			mac.chip(), mac.variant(), mac.pCores(), mac.eCores(), mac.gpuCores(), tier, r.changes().size());
		return r;
	}

	private static List<Change> recommend(Mac mac, int tier, double cpu) {
		List<Change> out = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		int rd = new int[] {12, 16, 20, 24, 32}[tier - 1];
		if (mac.memoryGB() <= 8) rd = Math.min(rd, 16);
		else if (mac.memoryGB() <= 16) rd = Math.min(rd, 24);
		if (cpu < 0.8) rd = Math.max(8, rd - 2);
		int rdNow = mc.options.renderDistance().get();
		final int rdTo = rd;
		if (rdNow != rd)
			out.add(new Change("Render Distance", rdNow + " chunks", rd + " chunks", rd > rdNow ? "your Mac can show more" : "smoother",
				() -> mc.options.renderDistance().set(rdTo)));
		int hz = Math.max(30, Math.min(MacSystem.displayHz(), 240));
		int fpsNow = mc.options.framerateLimit().get();
		if (fpsNow != hz)
			out.add(new Change("Max Framerate", fpsNow >= 260 ? "Unlimited" : fpsNow + " fps", hz + " fps", "matches your screen",
				() -> mc.options.framerateLimit().set(hz)));
		CadenceSettings.Preset preset = mac.fanless() || tier <= 3 ? CadenceSettings.Preset.BALANCED : CadenceSettings.Preset.QUALITY;
		if (CadenceSettings.current() != preset)
			out.add(new Change("Performance", CadenceSettings.current().label(), preset.label(),
				preset == CadenceSettings.Preset.BALANCED ? "cool and smooth" : "full quality", () -> CadenceSettings.apply(preset)));
		boolean smart = mac.fanless() || tier <= 2;
		if (smart != SmartLeaves.enabled())
			out.add(new Change("Leaves", SmartLeaves.enabled() ? "Smart" : "Detailed", smart ? "Smart" : "Detailed",
				smart ? "lighter forests" : "every leaf", () -> SmartLeaves.setEnabled(smart)));
		return out;
	}

	/** Applies the changes (render thread) and remembers that this Mac is set up. */
	public static void apply(Result r) {
		for (Change c : r.changes()) c.apply().run();
		Minecraft.getInstance().options.save();
		CadenceSettings.refreshLater();
		done(r.mac());
	}

	/** Answered (applied, kept, or not now) for this Mac: not offered again unless the Mac changes. */
	public static void done(Mac mac) {
		ShaderToggle.saveKey("setupMac", mac.product().isEmpty() ? mac.chip() : mac.product());
	}

	/** Whether to offer Quick Setup at the title screen (never answered on this Mac). */
	public static boolean shouldOffer() {
		if (System.getProperty("cadence.selftest") != null || Boolean.getBoolean("cadence.noQuickSetup")) return false;
		String saved = ShaderToggle.configValue("setupMac", "");
		Mac mac = detect();
		return !saved.equals(mac.product().isEmpty() ? mac.chip() : mac.product());
	}

	/** A fixed workload on every core, median of three timed runs after two warm-ups: ms (lower is faster). */
	static double cpuTest() {
		int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
		double[] runs = new double[5];
		for (int r = 0; r < 5; r++) {
			long t = System.nanoTime();
			Thread[] ts = new Thread[threads];
			double[] sink = new double[threads];
			for (int i = 0; i < threads; i++) {
				int k = i;
				ts[i] = new Thread(() -> {
					for (int j = 0; j < 4; j++) sink[k] += work(k * 31 + j);
				}, "cadence-quick-setup");
				ts[i].start();
			}
			try {
				for (Thread x : ts) x.join();
			} catch (InterruptedException e) {
				return -1;
			}
			runs[r] = (System.nanoTime() - t) / 1e6;
		}
		double[] timed = {runs[2], runs[3], runs[4]};
		java.util.Arrays.sort(timed);
		return timed[1];
	}

	private static double work(int seed) {
		long h = seed;
		double acc = 0;
		double[] arr = new double[4096];
		for (int i = 0; i < 16_000_000; i++) {
			h = h * 6364136223846793005L + 1442695040888963407L;
			int x = (int) (h >>> 33);
			acc += Math.sqrt((x & 1023) + 1.0) * ((x >> 10) & 255);
			arr[x & 4095] += acc * 1e-9;
		}
		return acc + arr[seed & 4095];
	}
}
