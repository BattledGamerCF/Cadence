package dev.cadence.shaders.cadence.packcheck;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.cadence.shaders.cadence.RenderScale;
import dev.cadence.shaders.cadence.ShadowReuse;
import dev.cadence.shaders.runtime.ShaderRuntime;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.slf4j.LoggerFactory;

/**
 * Cadence: "Check Pack for Speed-ups". Shadow receiver culling (skip drawing shadow casters that can't shadow anything you
 * can see; 5-7 ms a frame with BSL) is only safe if the pack never reads the shadow map anywhere else, which differs per
 * pack. Instead of a hand audit per pack, the pack is tested on this machine: from where you stand, the camera looks in
 * six directions; in each, the pack's picture is drawn three times from a cleared history with a frozen clock — culling
 * off, on, off again — and the pictures are compared pixel by pixel (the two "off" pictures show what the pack itself
 * varies, if anything). If "on" matches "off" in every direction, the speed-up is turned on for this pack, these options
 * and this dimension, and remembered (config/cadence-pack-checks.properties). If any picture differs, it stays off.
 * The world is paused and the time frozen while it runs (about 10 seconds); your view is put back afterwards.
 *
 * <p>Honest limits: it tests what is around you, not every place in every world; a shadow that is wrong only in a scene
 * the test didn't see is possible. Checking again in another place (or Reset in the settings) is always available.
 */
public final class PackCheck {
	public enum Result {
		SAFE, UNSAFE, INCONCLUSIVE
	}

	private static final int SEQUENCE_FRAMES = 10, WARM_FRAMES = 40;
	private static final float[][] VIEWS = {{0, 15}, {90, 15}, {180, 15}, {270, 15}, {0, 70}, {0, -35}};
	private static final Map<String, String> results = new TreeMap<>();
	private static boolean loaded;

	// the running check (render thread)
	private static volatile boolean running;
	private static volatile Boolean forced;
	private static volatile boolean sequenceStart;
	private static int view, phase, frame;
	private static float yaw0, pitch0;
	private static byte[] off1;
	private static long diffOn, diffOff;
	private static int worstOn = -1;
	private static GpuBuffer readback;
	private static volatile boolean captureDone;
	private static String runningKey, status = "";
	private static Result outcome;
	private static Runnable onFinish;

	private PackCheck() {
	}

	/** The key a result is stored under: pack content, dimension and every effective option. */
	public static String key(String fingerprint, String dimension, Map<String, String> options) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update((fingerprint + "|" + dimension + "|" + new TreeMap<>(options)).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest()).substring(0, 32);
		} catch (Exception e) {
			return fingerprint + "|" + dimension;
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("cadence-pack-checks.properties");
	}

	private static synchronized void load() {
		if (loaded) return;
		loaded = true;
		if (!Files.isRegularFile(file())) return;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
			p.load(r);
			for (String k : p.stringPropertyNames()) results.put(k, p.getProperty(k));
		} catch (IOException e) {
			// no saved checks
		}
	}

	private static synchronized void save() {
		Properties p = new Properties();
		p.putAll(results);
		try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
			p.store(w, "Cadence pack checks: safe = shadow receiver culling verified for that pack, options and dimension");
		} catch (IOException e) {
			LoggerFactory.getLogger("cadence").info("Pack check: can't save {}: {}", file(), e.toString());
		}
	}

	private static String currentKey() {
		ShaderRuntime runtime = ShaderRuntime.get();
		return runtime == null || runtime.programs() == null ? null : runtime.programs().checkKey;
	}

	/** The saved result for the pack as it runs now, or null if never checked. */
	public static Result savedResult() {
		load();
		String k = currentKey();
		String v = k == null ? null : results.get(k);
		if (v == null) return null;
		try {
			return Result.valueOf(v.split(" ")[0]);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Whether this pack (options, dimension) passed a check. */
	public static boolean verified() {
		return !running && savedResult() == Result.SAFE;
	}

	/** Forget every saved result (Settings: Reset). */
	public static void reset() {
		load();
		results.clear();
		save();
	}

	/** While a check runs: the culling it is testing (else null). */
	public static Boolean forcedCulling() {
		return running ? forced : null;
	}

	public static boolean frozen() {
		return running;
	}

	public static boolean consumeSequenceStart() {
		if (!sequenceStart) return false;
		sequenceStart = false;
		return true;
	}

	public static boolean running() {
		return running;
	}

	public static String status() {
		return status;
	}

	public static Result outcome() {
		return outcome;
	}

	/** Starts a check of the pack in use (render thread); onFinish runs when it ends. False if none can run now. */
	public static boolean start(Runnable onFinish) {
		Minecraft mc = Minecraft.getInstance();
		Entity camera = mc.getCameraEntity();
		if (running || camera == null || currentKey() == null) return false;
		PackCheck.onFinish = onFinish;
		runningKey = currentKey();
		yaw0 = camera.getYRot();
		pitch0 = camera.getXRot();
		view = 0;
		phase = 0;
		frame = 0;
		diffOn = diffOff = 0;
		worstOn = -1;
		outcome = null;
		off1 = null;
		forced = Boolean.FALSE;
		ShadowReuse.suspended = true;
		RenderScale.frozen = true;
		running = true;
		status = "Getting ready...";
		LoggerFactory.getLogger("cadence").info("Pack check started");
		return true;
	}

	/** Stops a check early (the screen was closed): nothing is saved. */
	public static void cancel() {
		if (!running) return;
		finish(null, "Cancelled");
	}

	private static void finish(Result result, String message) {
		running = false;
		forced = null;
		ShadowReuse.suspended = false;
		RenderScale.frozen = false;
		Entity camera = Minecraft.getInstance().getCameraEntity();
		if (camera != null) look(camera, yaw0, pitch0);
		outcome = result;
		status = message;
		if (result != null && runningKey != null) {
			load();
			results.put(runningKey, result.name() + " " + java.time.LocalDate.now());
			save();
		}
		LoggerFactory.getLogger("cadence").info("Pack check: {} ({}); 'on' pixels differing {}, between the two 'off' pictures {}", result,
			message, diffOn, diffOff);
		if (onFinish != null) onFinish.run();
	}

	private static void look(Entity e, float yaw, float pitch) {
		e.setYRot(yaw);
		e.setXRot(pitch);
		e.yRotO = yaw;
		e.xRotO = pitch;
		if (e instanceof net.minecraft.world.entity.LivingEntity living) {
			living.yHeadRot = yaw;
			living.yHeadRotO = yaw;
		}
	}

	/**
	 * ShaderRuntime.beginFrame, before the uniforms: advances the check one frame. Phases per view: 0 warm-up (until chunk
	 * building is done), 1 off, 2 on, 3 off again; each of 1-3 is a sequence of frames from a cleared history whose last
	 * frame is captured.
	 */
	public static void beforeFrame(ShaderRuntime runtime) {
		if (!running) return;
		if (!runningKey.equals(currentKey())) {
			finish(null, "The pack or dimension changed during the check");
			return;
		}
		Entity camera = Minecraft.getInstance().getCameraEntity();
		if (camera == null) {
			finish(null, "No camera");
			return;
		}
		look(camera, yaw0 + VIEWS[view][0], Math.max(-89, Math.min(89, VIEWS[view][1])));
		frame++;
		if (frame % 10 == 0) dev.cadence.shaders.cadence.CadenceSettings.refreshLater();
		if (phase == 0) {
			var chunks = Minecraft.getInstance().levelRenderer.sectionRenderDispatcher();
			boolean building = chunks != null && chunks.getCompileQueueSize() > 0;
			status = "Looking around (" + (view + 1) + " of " + VIEWS.length + ")...";
			if (frame >= WARM_FRAMES && !building || frame >= WARM_FRAMES * 8) beginSequence(runtime, 1);
			return;
		}
		if (frame == 1) {
			sequenceStart = true;
			runtime.targets().clearHistory();
		}
	}

	private static void beginSequence(ShaderRuntime runtime, int next) {
		phase = next;
		frame = 0;
		forced = next == 2;
		captureDone = false;
	}

	private static int retries, lastW, lastH;

	/** ShaderRuntime.endFrame, after the pack's final program: captures a sequence's last frame. */
	public static void afterFinal(GpuTexture picture) {
		if (running && phase != 0 && frame == SEQUENCE_FRAMES) {
			var chunks = Minecraft.getInstance().levelRenderer.sectionRenderDispatcher();
			if (chunks != null && chunks.getCompileQueueSize() > 0 && retries < 3) {
				// geometry still changing: this direction starts over once it has settled
				retries++;
				phase = 0;
				frame = 0;
				forced = Boolean.FALSE;
				return;
			}
		}
		if (!running || phase == 0 || frame != SEQUENCE_FRAMES) {
			if (running && phase != 0 && frame > SEQUENCE_FRAMES && captureDone) advance();
			return;
		}
		int w = picture.getWidth(0), h = picture.getHeight(0);
		lastW = w;
		lastH = h;
		long bytes = (long) w * h * picture.getFormat().blockSize();
		var device = RenderSystem.getDevice();
		if (readback == null || readback.size() != bytes) {
			if (readback != null) readback.close();
			readback = device.createBuffer(() -> "Cadence pack check", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
		}
		int capturedPhase = phase;
		device.createCommandEncoder().copyTextureToBuffer(picture, readback, 0, () -> compare(capturedPhase), 0);
	}

	private static void compare(int capturedPhase) {
		byte[] now;
		try (var view = readback.slice().map(true, false)) {
			ByteBuffer data = view.data();
			now = new byte[data.remaining()];
			data.get(now);
		}
		if (Boolean.getBoolean("cadence.packCheckDump")) {
			try {
				java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("user.dir"),
					"packcheck-v" + (view + 1) + "-p" + capturedPhase + "-" + lastW + "x" + lastH + ".rgba"), now);
			} catch (java.io.IOException e) {
				// diagnostics only
			}
		}
		if (capturedPhase == 1) off1 = now;
		else {
			long differing = differing(off1, now);
			LoggerFactory.getLogger("cadence").info("Pack check: view {} {} vs first 'off': {} pixels differ", view + 1,
				capturedPhase == 2 ? "'on'" : "second 'off'", differing);
			if (capturedPhase == 2) {
				diffOn += differing;
				worstOn = Math.max(worstOn, (int) Math.min(Integer.MAX_VALUE, differing));
			} else diffOff += differing;
		}
		captureDone = true;
	}

	/** Pixels where any channel differs by more than 1 (8-bit). */
	private static long differing(byte[] a, byte[] b) {
		if (a == null || b == null || a.length != b.length) return Long.MAX_VALUE / 4;
		long n = 0;
		for (int i = 0; i + 3 < a.length; i += 4) {
			if (Math.abs((a[i] & 255) - (b[i] & 255)) > 1 || Math.abs((a[i + 1] & 255) - (b[i + 1] & 255)) > 1
				|| Math.abs((a[i + 2] & 255) - (b[i + 2] & 255)) > 1) n++;
		}
		return n;
	}

	private static void advance() {
		if (phase < 3) {
			beginSequence(null, phase + 1);
			return;
		}
		// one direction done
		view++;
		retries = 0;
		phase = 0;
		frame = 0;
		forced = Boolean.FALSE;
		if (view < VIEWS.length) return;
		Result result;
		String message;
		// the pack's own run-to-run noise (the two 'off' pictures) sets the bar: GPUs aren't bit-exact in every pass
		long pixels = (long) VIEWS.length * lastW * lastH;
		long allowed = Math.max(2 * diffOff, pixels / 100_000);
		if (diffOff > pixels / 1000) {
			result = Result.INCONCLUSIVE;
			message = "Couldn't check: this pack's picture changes by itself";
		} else if (diffOn <= allowed) {
			result = Result.SAFE;
			message = "Safe: faster shadows turned on for this pack";
		} else {
			result = Result.UNSAFE;
			message = "Not safe for this pack: shadows changed in the test";
		}
		finish(result, message);
	}
}
