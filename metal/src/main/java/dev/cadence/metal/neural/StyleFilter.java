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
 * Style Filters (Video Settings > Cadence: Apple Hardware > Style Filter): the world repainted in the style of one of the game's
 * own paintings, by a neural network on the Neural Engine (mcneural.m: about 28 ms a picture on an M2, beside the GPU; the newest
 * picture is blended over the world, the hand and the HUD drawn on top as always).
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
	private static volatile boolean busy;
	private static volatile long ready;  // a loaded network handed over by the loading thread
	private static volatile Style readyFor;
	// pictures of the world for training, taken every half second while a style waits for them
	private static Path capturing;
	private static int captured, frameCounter;

	private StyleFilter() {
	}

	public static Style style() {
		return style;
	}

	public static void setStyle(Style s) {
		style = s == null ? Style.OFF : s;
		status = "";
	}

	/** What the chosen style is doing: "" (running or off), "Training 42%", "Taking pictures", or why it can't run. */
	public static String status() {
		return status;
	}

	private static Path dir() {
		return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Cadence", "styles");
	}

	/** Render thread, after the world is drawn (before the hand and the HUD): the newest styled picture over it. */
	public static void afterWorld(GpuTexture color) {
		Style s = style;
		if (s == Style.OFF) {
			if (handle != 0) drop();
			return;
		}
		if (ready != 0) {
			if (handle != 0) drop();
			handle = ready;
			loadedFor = readyFor;
			ready = 0;
		}
		if (handle != 0 && loadedFor != s) drop();
		if (handle != 0) {
			NeuralNative.styleFrame(handle, enc, MetalBridge.textureHandle(color), STRENGTH);
			return;
		}
		if (capturing != null) {
			capture();
			return;
		}
		if (!busy) prepare(s);
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
			status = "Can't: " + t.getMessage();
			style = Style.OFF;
			System.out.println("[cadence] style filter: " + t);
		}
	}

	private static void capture() {
		if (++frameCounter % 30 != 0) return;  // (a picture every 30 frames: the world as the player moves through it)
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
			status = "Can't: no Cadence Helper";
			return;
		}
		busy = true;
		status = "Training 0%";
		Thread t = new Thread(() -> {
			try {
				Path tmp = model.resolveSibling(s.painting + ".training.mlmodel");
				Process p = new ProcessBuilder("/usr/bin/nice", "-n", "15", helper.toString(), "train-style", painting.toString(), content.toString(),
					String.valueOf(ITERATIONS), tmp.toString()).redirectErrorStream(true).start();
				try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
					for (String line; (line = r.readLine()) != null;) {
						// Create ML's table: "| 42 | ..." a row per iteration
						var m = java.util.regex.Pattern.compile("^\\|\\s*(\\d+)\\s*\\|").matcher(line);
						if (m.find()) status = "Training " + Math.min(99, Integer.parseInt(m.group(1)) * 100 / ITERATIONS) + "%";
						if (line.startsWith("error:")) System.out.println("[cadence] style filter: " + line);
					}
				}
				if (p.waitFor() == 0 && Files.exists(tmp)) {
					Files.move(tmp, model, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					System.out.println("[cadence] style filter: " + s.label + " trained");
					status = "";
					busy = false;
					if (style == s) load(s, model);
				} else {
					status = "Can't: training failed";
					busy = false;
				}
			} catch (Exception e) {
				status = "Can't: " + e.getMessage();
				busy = false;
			}
		}, "cadence-style-train");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	/** The network loaded (compiled by Core ML the first time) off the render thread, then handed over. */
	private static void load(Style s, Path model) {
		busy = true;
		Thread t = new Thread(() -> {
			long src = 0, cmp = 0, err = MemoryUtil.nmemCalloc(1, 1024);
			try {
				src = MemoryUtil.memAddress(MemoryUtil.memUTF8(model.toString()));
				cmp = MemoryUtil.memAddress(MemoryUtil.memUTF8(model.resolveSibling(s.painting + ".mlmodelc").toString()));
				long h = NeuralNative.styleNew(ctx, src, cmp, err, 1024);
				if (h == 0) {
					status = "Can't: " + MemoryUtil.memUTF8(err);
				} else {
					readyFor = s;
					ready = h;
					System.out.println("[cadence] style filter: " + s.label + " on the Neural Engine");
				}
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
