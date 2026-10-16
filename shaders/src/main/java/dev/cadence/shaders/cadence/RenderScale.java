package dev.cadence.shaders.cadence;

import java.util.Locale;

/**
 * Cadence: the shader packs' render scale, 0.33 to 1.0 of the window (1.0 = off, exactly the original rendering), or Auto.
 * Saved as renderScale= in config/cadence-shaders.properties ("auto" or a number); Video Settings changes it live
 * (next frame). -Dcadence.shaders.renderScale overrides it for tests.
 *
 * <p>Auto is dynamic resolution: each pack frame reports its time (frame). Once a second, when frames are over 10% too slow
 * for the target frame rate (autoTarget=, default 60) and the GPU is the reason (its time per frame, Cadence GpuBusy, is near
 * the budget), the scale drops to the value that should hit it (pixel cost goes with scale squared); when the GPU has 25% to
 * spare for two windows running it rises one 0.05 step, also under VSync (the GPU time still shows the headroom). Never
 * outside autoMin..1.0 (config autoMin=, default 0.5). A CPU-bound frame changes nothing: a lower resolution would cost sharpness and gain nothing. A 2-second
 * hold after every change keeps resizes (the pack's targets are reallocated) rare. A fanless Mac slows as it warms; Auto
 * gives back resolution instead of frame rate.
 */
public final class RenderScale {
	/** Fixed scales go down to MIN; Auto stays at or above autoMin (config autoMin=, default 0.5). */
	public static final float MIN = 0.33f, MAX = 1.0f, STEP = 0.05f;
	private static volatile float autoMin = 0.5f;
	private static volatile boolean auto;
	private static volatile float scale;
	private static volatile int autoTarget = 60;

	static {
		String prop = System.getProperty("cadence.shaders.renderScale");
		String value = prop != null ? prop : ShaderToggle.configValue("renderScale", "auto");
		applyValue(value);
		try {
			autoTarget = Math.max(20, Math.min(240, Integer.parseInt(ShaderToggle.configValue("autoTarget", "60").strip())));
		} catch (NumberFormatException e) {
			autoTarget = 60;
		}
		try {
			autoMin = Math.max(MIN, Math.min(MAX, Float.parseFloat(ShaderToggle.configValue("autoMin", "0.5").strip())));
		} catch (NumberFormatException e) {
			autoMin = 0.5f;
		}
	}

	private RenderScale() {
	}

	public static float get() {
		return scale;
	}

	public static boolean isAuto() {
		return auto;
	}

	public static int autoTarget() {
		return autoTarget;
	}

	/** Auto's frame-rate goal (presets: 40 Battery Saver, 60 otherwise); saved as autoTarget=. */
	public static void setAutoTarget(int fps) {
		autoTarget = Math.max(20, Math.min(240, fps));
		ShaderToggle.saveKey("autoTarget", Integer.toString(autoTarget));
	}

	public static float autoMin() {
		return autoMin;
	}

	/**
	 * upscaler= in the config: "metalfx" (default: Apple's spatial scaler; measured the same GPU cost as the shader and sharper
	 * edges) or "cadence" (Catmull-Rom + adaptive sharpen; also the automatic fallback if MetalFX can't run).
	 * -Dcadence.upscaler=cadence|metalfx overrides it for tests.
	 */
	// Cadence: three upscalers. 0 Apple (MetalFX), 1 Smooth (Cadence bicubic + sharpen), 2 Fast (bilinear, cheapest)
	public static final int UPSCALE_APPLE = 0, UPSCALE_SMOOTH = 1, UPSCALE_FAST = 2;
	private static volatile int upscaler = parseUpscaler(
		System.getProperty("cadence.upscaler", ShaderToggle.configValue("upscaler", "metalfx")));

	private static int parseUpscaler(String value) {
		return switch (value.strip().toLowerCase(Locale.ROOT)) {
			case "cadence", "smooth" -> UPSCALE_SMOOTH;
			case "fast", "bilinear" -> UPSCALE_FAST;
			default -> UPSCALE_APPLE;
		};
	}

	public static int upscaler() {
		return upscaler;
	}

	/** The upscaler, also saved (upscaler= metalfx | cadence | fast). */
	public static void setUpscaler(int mode) {
		upscaler = mode;
		ShaderToggle.saveKey("upscaler", mode == UPSCALE_SMOOTH ? "cadence" : mode == UPSCALE_FAST ? "fast" : "metalfx");
	}

	/** For the self-test: not saved. */
	public static void applyUpscaler(String value) {
		upscaler = parseUpscaler(value);
	}

	public static boolean metalFx() {
		return upscaler == UPSCALE_APPLE;
	}

	public static boolean fastUpscale() {
		return upscaler == UPSCALE_FAST;
	}

	private static volatile double lastIntervalMs, lastGpuMs;

	/** The last completed 1-second window's frame interval and GPU time (ms per frame), and how many windows so far. */
	public static double lastIntervalMs() {
		return lastIntervalMs;
	}

	public static double lastGpuMs() {
		return lastGpuMs;
	}

	public static long windows() {
		return windowCount;
	}

	private static volatile long windowCount;

	/** The last 1-second window: frame interval and GPU time per frame, for the self-test log. */
	public static String lastWindow() {
		return String.format(Locale.ROOT, "interval %.1f ms gpu %.1f ms", lastIntervalMs, lastGpuMs);
	}

	/** Sets a fixed scale and saves it. */
	public static void set(float value) {
		auto = false;
		scale = clamp(value);
		windowStart = 0;
		ShaderToggle.saveKey("renderScale", String.format(Locale.ROOT, "%.2f", scale));
	}

	/** Turns Auto on (starting from the current scale) and saves it. */
	public static void setAuto() {
		auto = true;
		if (scale < autoMin) scale = autoMin;
		windowStart = 0;
		ShaderToggle.saveKey("renderScale", "auto");
	}

	/** Cadence (HeatControl/MemoryGuard): Auto's temporary upper limit (1.0 = none). Fixed scales are the player's and stay. */
	private static volatile float ceiling = MAX;

	public static void setCeiling(float c) {
		ceiling = Math.max(MIN, Math.min(MAX, c));
	}

	public static float ceiling() {
		return ceiling;
	}

	/** Auto starts over at full size (shaders turned off: the pack's lower scale says nothing about vanilla's cost). */
	public static void restartAuto() {
		if (!auto) return;
		scale = MAX;
		upVotes = 0;
		windowStart = 0;
		holdUntil = 0;
	}

	/** For the self-test: "auto" or a number. */
	public static void applyValue(String value) {
		String v = value.strip().toLowerCase(Locale.ROOT);
		if (v.equals("auto")) {
			auto = true;
			if (scale == 0f) scale = MAX;
			return;
		}
		auto = false;
		try {
			scale = clamp(Float.parseFloat(v));
		} catch (NumberFormatException e) {
			scale = MAX;
		}
	}

	/** Sharpening for the upscale: none at full size, more the more pixels are being made up. Quantized (one pipeline each). */
	public static float sharpenFor(float s) {
		if (s >= 0.999f) return 0f;
		float k = Math.min(0.5f, (1f - s) * 0.8f);
		return Math.round(k * 10f) / 10f;
	}

	// ---- Auto: frame-time window, on the render thread only ----
	private static long windowStart, lastFrame, holdUntil, windowGpuStart;
	private static long lastChangeAt, riseBlockedUntil, riseBackoff = 10_000_000_000L;
	private static boolean lastChangeWasRise;
	private static int frames, upVotes;
	private static double busySum;

	/** Auto's last scale per pack this session (packSwitched): a pack starts where it settled before, a new one at full size. */
	private static final java.util.Map<String, Float> PACK_SCALE = new java.util.HashMap<>();

	/** Cadence (ShaderSwitcher): the pack changed from {@code from} to {@code to} ("" = none). Render thread. */
	public static void packSwitched(String from, String to) {
		if (!auto) return;
		if (!from.isEmpty()) PACK_SCALE.put(from, scale);
		if (!to.isEmpty()) {
			scale = Math.max(autoMin, PACK_SCALE.getOrDefault(to, MAX));
			upVotes = 0;
		}
	}

	/** A resize or a pack reload just happened: its first frames are not representative (empty or reallocating). */
	public static void settle() {
		holdUntil = System.nanoTime() + 2_000_000_000L;
		windowStart = 0;
		upVotes = 0;
	}

	/**
	 * Called once per pack frame (ShaderRuntime.beginFrame). {@code chunksBuilding}: the world's chunk build queue is busy;
	 * frames are then cheaper than they will be (terrain still missing), so they never justify a higher scale.
	 */
	// every pack frame's interval over the last ~10 s (ring), for the self-test's stutter figures
	private static final float[] RING = new float[4096];
	private static final long[] RING_AT = new long[4096];
	private static int ringNext;

	/** "worst X ms, 99th percentile Y ms" of pack frame intervals in the last 10 s. */
	public static String stutter() {
		long now = System.nanoTime();
		float[] v = new float[RING.length];
		int n = 0;
		for (int i = 0; i < RING.length; i++) if (RING_AT[i] != 0 && now - RING_AT[i] < 10_000_000_000L) v[n++] = RING[i];
		if (n == 0) return "no pack frames";
		java.util.Arrays.sort(v, 0, n);
		return String.format(Locale.ROOT, "worst %.0f ms, 99th pct %.0f ms over %d frames", v[n - 1], v[Math.min(n - 1, (int) (n * 0.99))], n);
	}

	/** Cadence (PackCheck): the scale is held while a pack check runs. */
	public static volatile boolean frozen;

	/** Frames drawn so far, and the render thread's id (self-test A/B: its CPU time per frame). */
	public static volatile long frameCount;
	public static volatile long renderThreadId = -1;

	public static void frame(long nowNanos, boolean chunksBuilding) {
		frameCount++;
		if (renderThreadId < 0) renderThreadId = Thread.currentThread().threadId();
		if (frozen) return;
		if (chunksBuilding) upVotes = 0;
		if (lastFrame != 0) {
			double ms = (nowNanos - lastFrame) / 1e6;
			RING[ringNext] = (float) ms;
			RING_AT[ringNext] = nowNanos;
			ringNext = (ringNext + 1) % RING.length;
			if (ms < 250) { // a pause (menu, loading) is not a slow frame
				busySum += ms;
				frames++;
			}
		}
		lastFrame = nowNanos;
		if (dev.cadence.shaders.cadence.system.BackgroundFps.fpsCap() != Integer.MAX_VALUE) {
			// another app is active and drawing is held at a few fps (BackgroundFps): those frames say nothing about the GPU,
			// so Auto keeps its scale and measures afresh once Minecraft is active again
			windowStart = 0;
			holdUntil = nowNanos + 2_000_000_000L;
			return;
		}
		if (nowNanos < holdUntil) {
			windowStart = 0;
			return;
		}
		long gpuNow = dev.cadence.metal.GpuBusy.totalNanos();
		if (windowStart == 0) {
			windowStart = nowNanos;
			windowGpuStart = gpuNow;
			frames = 0;
			busySum = 0;
			return;
		}
		if (nowNanos - windowStart < 1_000_000_000L || frames < 10) return;
		double intervalMs = busySum / frames;
		// GPU time per frame: still shows headroom when VSync or a frame cap holds the interval at the target, and tells a
		// CPU-bound frame (lowering resolution would only cost sharpness) from a GPU-bound one. No GPU timing: the interval.
		double gpuMs = gpuNow > windowGpuStart ? (gpuNow - windowGpuStart) / 1e6 / frames : intervalMs;
		windowGpuStart = gpuNow;
		lastIntervalMs = intervalMs;
		lastGpuMs = gpuMs;
		windowCount++;
		if (!auto) { // a fixed scale: measured (lastWindow) but never changed
			windowStart = nowNanos;
			frames = 0;
			busySum = 0;
			return;
		}
		double targetMs = 1000.0 / autoTarget;
		float next = scale;
		QualityHint.window(scale, intervalMs, gpuMs, targetMs);
		boolean slow = intervalMs > targetMs * 1.10 && gpuMs > targetMs * 0.85;
		boolean fast = gpuMs < targetMs * 0.75 && intervalMs < targetMs * 1.10 && !chunksBuilding;
		// Cadence: a rise that a drop soon undoes blocks the next rise for a while (10 s, doubling to 2 min while it keeps
		// happening, back to 10 s after a minute without a change): Auto used to step up and back down every few seconds.
		if (lastChangeAt != 0 && nowNanos - lastChangeAt > 60_000_000_000L) riseBackoff = 10_000_000_000L;
		if (fast && nowNanos < riseBlockedUntil) fast = false;
		upVotes = fast ? upVotes + 1 : 0;
		if (fast && upVotes < 2) fast = false; // up only after two windows agree; down at once
		if (slow || fast) {
			// GPU work goes with pixel count, i.e. scale squared. Down: jump to the scale that should hit the target (aiming 5%
			// under it), 0.05-quantized; converges in one or two resizes (each reallocates the pack's targets, a short hitch).
			// Up: one step at a time (frame time is not all GPU: overshooting up costs a resize back down), except with the GPU
			// under half the budget: then to the scale that should use 75% of it. Packs' frames are partly per-vertex work that
			// doesn't grow with pixels, so scale squared overestimates the rise's cost (it lands under 75%). Cadence: a light pack
			// after a heavy one, or a cooled-down Mac, came back to full size 0.05 every few seconds (20-30 s blurry).
			double wanted = slow ? scale * Math.sqrt(targetMs * 0.95 / intervalMs)
				: gpuMs < targetMs * 0.5 ? Math.max(scale + STEP, scale * Math.sqrt(targetMs * 0.75 / gpuMs)) : scale + STEP;
			next = Math.max(autoMin, clamp(Math.round((float) wanted / STEP) * STEP));
		}
		// the heat/memory ceiling only with a pack: without one a lower scale costs more than it saves (measured)
		if (dev.cadence.shaders.runtime.ShaderRuntime.get() != null) next = Math.min(next, ceiling);
		next = Math.round(next * 100f) / 100f;
		if (next != scale) {
			boolean down = next < scale;
			if (down && lastChangeWasRise && nowNanos - lastChangeAt < 30_000_000_000L) {
				riseBlockedUntil = nowNanos + riseBackoff;
				riseBackoff = Math.min(riseBackoff * 2, 120_000_000_000L);
			}
			lastChangeWasRise = !down;
			lastChangeAt = nowNanos;
			scale = next;
			upVotes = 0;
			holdUntil = nowNanos + 2_000_000_000L;
		}
		windowStart = nowNanos;
		frames = 0;
		busySum = 0;
	}

	private static float clamp(float v) {
		if (Float.isNaN(v)) return MAX;
		return Math.max(MIN, Math.min(MAX, v));
	}
}
