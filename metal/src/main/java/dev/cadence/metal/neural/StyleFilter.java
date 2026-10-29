package dev.cadence.metal.neural;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.cadence.metal.MetalBridge;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * Style Filters (Video Settings > Cadence: Visuals > Style Filter): the world repainted in the style of one of the game's
 * own paintings, by a neural network on the Neural Engine (mcneural.m: about 8.5 ms a picture on an M2, beside the GPU, at most 60
 * a second; the newest picture is blended over the world, the hand and the HUD drawn on top as always). The trained network is
 * fitted to the Neural Engine and the window's shape first (StyleSpec).
 *
 * <p>Each style's network is trained on this Mac the first time it's chosen: Cadence Helper runs Create ML's style transfer
 * (about 4 minutes on an M2, in the background at low priority) with the painting (from the game's resources, so a resource pack's
 * painting gives its own style) and a few pictures of the world (taken as you play, or your screenshots). Nothing is downloaded;
 * the trained networks stay in ~/Library/Application Support/Cadence/styles.
 */
public final class StyleFilter {
	/** -Dcadence.style.iterations: Create ML's training iterations per style (default 300). */
	private static final int ITERATIONS = Integer.getInteger("cadence.style.iterations", 300);
	/** -Dcadence.style.strength: how much of the styled picture is shown (default 0.85). */
	private static final float STRENGTH = Float.parseFloat(System.getProperty("cadence.style.strength", "0.85"));
	private static final int CONTENT_PICTURES = 8;

	public enum Style {
		OFF("Off", null), SUNFLOWERS("Sunflowers", "sunflowers"), SUNSET("Sunset", "sunset"), WANDERER("Wanderer", "wanderer"), POND("Pond", "pond");

		public final String label, painting;

		Style(String label, String painting) {
			this.label = label;
			this.painting = painting;
		}
	}

	private static volatile Style style = Style.OFF;
	private static volatile String status = "";
	private static long handle, ctx, enc;
	private static Style loadedFor;
	/** The model's input size the loaded network was fitted to (a window of another shape loads it again). */
	private static String loadedSize = "";
	private static volatile boolean busy;
	private static volatile long ready;  // a loaded network handed over by the loading thread
	private static volatile Style readyFor;
	private static volatile String readySize = "";
	/** The style whose training or loading failed: not tried again until another style is chosen (BH7-02). */
	private static volatile Style failedFor;
	/** The running training (destroyed when the style is turned off or another is chosen, BH7-15). */
	private static volatile Process training;
	private static volatile Style trainingFor;
	// pictures of the world for training, taken every half second while a style waits for them
	private static Path capturing;
	private static int captured;
	private static long capturedAt;

	private StyleFilter() {
	}

	public static Style style() {
		return style;
	}

	public static void setStyle(Style s) {
		style = s == null ? Style.OFF : s;
		status = "";
		failedFor = null;
		Process p = training;
		if (p != null && trainingFor != style) p.destroy();  // (the training thread then clears busy)
	}

	/** The world closed (WorldClosed): the network let go; a capture under way stops. Render thread. */
	public static void release() {
		if (handle != 0) drop();
		capturing = null;
	}

	private static void failed(Style s, String why) {
		status = "Can't: " + why;
		failedFor = s;
		System.out.println("[cadence] style filter: " + s.label + ": " + why);
	}

	/** What the chosen style is doing: "" (running or off), "Training 42%", "Taking pictures", or why it can't run. */
	public static String status() {
		return status;
	}

	private static Path dir() {
		return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Cadence", "styles");
	}

	/**
	 * Render thread, after the world is drawn (before the hand and the HUD): the newest styled picture over it. color null: not
	 * this frame (a shader pack, World Resolution below 100%): the network is let go, as when the style is off.
	 */
	public static void afterWorld(GpuTexture color) {
		Style s = style;
		if (s == Style.OFF || color == null) {
			if (handle != 0) drop();
			if (ready != 0) {
				long h = ready;
				ready = 0;
				NeuralNative.styleFree(h);
			}
			capturing = null;
			return;
		}
		if (ready != 0) {
			if (handle != 0) drop();
			handle = ready;
			loadedFor = readyFor;
			loadedSize = readySize;
			ready = 0;
		}
		if (handle != 0 && loadedFor != s) drop();
		if (handle != 0) {
			NeuralNative.styleFrame(handle, enc, MetalBridge.textureHandle(color), STRENGTH);
			// (the window changed shape: fitted again, the old network shown until the new one is ready)
			if (!busy && failedFor != s && !sizeKey(color.getWidth(0), color.getHeight(0)).equals(loadedSize)) prepare(s);
			return;
		}
		if (capturing != null) {
			capture();
			return;
		}
		if (!busy && failedFor != s) prepare(s);
	}

	private static void prepare(Style s) {
		try {
			if (ctx == 0) {
				Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
				ctx = MetalBridge.ctx(encoder);
				enc = MetalBridge.enc(encoder);
			}
			Path d = dir();
			Files.createDirectories(d);
			Path model = d.resolve(s.painting + ".mlmodel");
			if (Files.exists(model)) {
				load(s, model);
				return;
			}
			// training: the painting, and pictures of the world (your screenshots if there are enough, else taken now)
			Path content = d.resolve("content");
			Files.createDirectories(content);
			long have;
			try (var files = Files.list(content)) {
				have = files.filter(p -> p.toString().endsWith(".png")).count();
			}
			if (have < CONTENT_PICTURES) {
				Path shots = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
				if (Files.isDirectory(shots)) {
					try (var files = Files.list(shots)) {
						for (Path p : files.filter(p -> p.toString().endsWith(".png")).limit(CONTENT_PICTURES).toList())
							Files.copy(p, content.resolve("shot-" + p.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
				}
				try (var files = Files.list(content)) {
					have = files.filter(p -> p.toString().endsWith(".png")).count();
				}
			}
			if (have < CONTENT_PICTURES) {
				capturing = content;
				captured = (int) have;
				status = "Taking pictures";
				return;
			}
			Path painting = d.resolve(s.painting + ".png");
			writePainting(s, painting);
			train(s, painting, content, model);
		} catch (Throwable t) {
			failed(s, String.valueOf(t.getMessage()));
		}
	}

	private static void capture() {
		long now = System.nanoTime();
		if (now - capturedAt < 500_000_000L) return;  // (a picture every half second: the world as the player moves through it)
		capturedAt = now;
		Path to = capturing.resolve("world-" + System.currentTimeMillis() + ".png");
		Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> {
			try (NativeImage img = image) {
				img.writeToFile(to);
			} catch (Exception e) {
				System.out.println("[cadence] style filter: picture not saved: " + e);
			}
		});
		if (++captured >= CONTENT_PICTURES) capturing = null;
	}

	/** The painting from the game's resources (a resource pack's own if it has one), scaled up by whole pixels to 512. */
	private static void writePainting(Style s, Path out) throws Exception {
		var mc = Minecraft.getInstance();
		var res = mc.getResourceManager().getResource(Identifier.withDefaultNamespace("textures/painting/" + s.painting + ".png")).orElseThrow();
		try (var in = res.open(); NativeImage src = NativeImage.read(in)) {
			int k = Math.max(1, 512 / Math.max(src.getWidth(), src.getHeight()));
			try (NativeImage big = new NativeImage(src.getWidth() * k, src.getHeight() * k, false)) {
				for (int y = 0; y < big.getHeight(); y++)
					for (int x = 0; x < big.getWidth(); x++) big.setPixel(x, y, src.getPixel(x / k, y / k));
				big.writeToFile(out);
			}
		}
	}

	private static void train(Style s, Path painting, Path content, Path model) {
		Path helper = Helper.binary();
		if (helper == null) {
			failed(s, "no Cadence Helper");
			return;
		}
		busy = true;
		trainingFor = s;
		status = "Training 0%";
		Thread t = new Thread(() -> {
			try {
				Path tmp = model.resolveSibling(s.painting + ".training.mlmodel");
				Process p = new ProcessBuilder("/usr/bin/nice", "-n", "15", helper.toString(), "train-style", painting.toString(), content.toString(),
					String.valueOf(ITERATIONS), tmp.toString()).redirectErrorStream(true).start();
				training = p;
				if (style != s) p.destroy();  // (turned off while it started)
				var row = java.util.regex.Pattern.compile("^\\|\\s*(\\d+)\\s*\\|");
				try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
					for (String line; (line = r.readLine()) != null;) {
						// Create ML's table: "| 42 | ..." a row per iteration
						var m = row.matcher(line);
						if (m.find() && style == s) status = "Training " + Math.min(99, Integer.parseInt(m.group(1)) * 100 / ITERATIONS) + "%";
						if (line.startsWith("error:")) System.out.println("[cadence] style filter: " + line);
					}
				}
				int code = p.waitFor();
				training = null;
				if (code == 0 && Files.exists(tmp)) {
					Files.move(tmp, model, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					System.out.println("[cadence] style filter: " + s.label + " trained");
					if (style == s) status = "";
					busy = false;
					if (style == s) load(s, model);
				} else {
					Files.deleteIfExists(tmp);
					busy = false;
					if (style == s) failed(s, "training failed");
					else System.out.println("[cadence] style filter: " + s.label + " training stopped");
				}
			} catch (Exception e) {
				training = null;
				busy = false;
				if (style == s) failed(s, String.valueOf(e.getMessage()));
			}
		}, "cadence-style-train");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	private static String sizeKey(int w, int h) {
		int[] size = StyleSpec.size(w, h);
		return size[0] + "x" + size[1];
	}

	/**
	 * The network fitted to the Neural Engine and the window's shape (StyleSpec; the network as trained if that can't be done)
	 * and loaded (compiled by Core ML the first time) off the render thread, then handed over.
	 */
	private static void load(Style s, Path model) {
		busy = true;
		var window = Minecraft.getInstance().getWindow();
		int[] size = StyleSpec.size(window.getWidth(), window.getHeight());
		String key = size[0] + "x" + size[1];
		Thread t = new Thread(() -> {
			long src = 0, cmp = 0, err = MemoryUtil.nmemCalloc(1, 1024);
			try {
				Path use = model, compiled = model.resolveSibling(s.painting + ".mlmodelc");
				int margin = 0;
				Path fitted = model.resolveSibling(s.painting + ".ane-" + key + ".mlmodel");
				try {
					if (Files.exists(fitted) || StyleSpec.rewrite(model, fitted, size[0], size[1])) {
						use = fitted;
						compiled = model.resolveSibling(s.painting + ".ane-" + key + ".mlmodelc");
						margin = StyleSpec.MARGIN;
					}
				} catch (Exception e) {
					System.out.println("[cadence] style filter: network not fitted (" + e + "), used as trained");
				}
				src = MemoryUtil.memAddress(MemoryUtil.memUTF8(use.toString()));
				cmp = MemoryUtil.memAddress(MemoryUtil.memUTF8(compiled.toString()));
				long h = NeuralNative.styleNew(ctx, src, cmp, margin, err, 1024);
				if (h == 0) {
					failed(s, MemoryUtil.memUTF8(err));
				} else if (style != s) {
					NeuralNative.styleFree(h);  // (turned off or changed while it loaded)
				} else {
					readyFor = s;
					readySize = key;
					ready = h;
					System.out.println("[cadence] style filter: " + s.label + " on the Neural Engine (" + key + ")");
					forgetOtherSizes(s, key);
				}
			} catch (Throwable e) {
				// (marked failed, so the next frame doesn't start another loader)
				failed(s, String.valueOf(e));
			} finally {
				if (src != 0) MemoryUtil.nmemFree(src);
				if (cmp != 0) MemoryUtil.nmemFree(cmp);
				MemoryUtil.nmemFree(err);
				busy = false;
			}
		}, "cadence-style-load");
		t.setDaemon(true);
		t.start();
	}

	/** The style's networks fitted to other window shapes deleted (each is a few MB; the window's shape rarely changes back). */
	private static void forgetOtherSizes(Style s, String keep) {
		try (var files = Files.list(dir())) {
			for (Path p : files.toList()) {
				String n = p.getFileName().toString();
				if (!n.startsWith(s.painting + ".ane-") || n.startsWith(s.painting + ".ane-" + keep + ".")) continue;
				if (Files.isDirectory(p)) {
					try (var walk = Files.walk(p)) {
						for (Path q : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
					}
				} else {
					Files.deleteIfExists(p);
				}
			}
		} catch (Exception ignored) {
		}
	}

	/** Frames run through the network, frames sent while it was busy, the last run's ms. */
	public static double[] stats() {
		if (handle == 0) return new double[3];
		long b = MemoryUtil.nmemAlloc(24);
		try {
			NeuralNative.styleStats(handle, b);
			return new double[] {MemoryUtil.memGetLong(b), MemoryUtil.memGetLong(b + 8), MemoryUtil.memGetLong(b + 16) / 100.0};
		} finally {
			MemoryUtil.nmemFree(b);
		}
	}

	private static void drop() {
		long h = handle;
		handle = 0;
		loadedFor = null;
		// (frames in flight may still use it: let go a second later, off the render thread)
		Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException ignored) {
			}
			NeuralNative.styleFree(h);
		});
	}
}
