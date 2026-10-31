package dev.cadence.metal.rec;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.cadence.metal.backend.MetalBridge;
import java.io.InputStream;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.system.MemoryUtil;

/**
 * Instant Replay (Video Settings > Instant Replay; the Save Replay key): the last 30 seconds of what's on screen kept in memory
 * as hardware-encoded HEVC, 1920 wide (10-bit HLG with HDR on; H.264 at 1280 wide when HEVC can't be had; rec.m mcrp_*: the
 * recorder's GPU conversion into an IOSurface pixel buffer, VideoToolbox, a ring of encoded frames, about 45 MB), saved on request to replays/replay-DATE.mp4 by AVFoundation (nothing re-encoded).
 * At most 60 frames a second are kept, each at the time it was shown; no sound. Off by default: on, it costs a GPU dispatch
 * per kept frame (~0.1 ms) and the Mac's media encoder.
 */
public final class Replay {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final int PARAMS_BYTES = 64, SECONDS = 30, FPS = 60;
	/** -Dcadence.replay.codec=h264|hevc: the codec (default hevc: the same picture at about half the size, so 1080p for 720p's memory). */
	private static final boolean HEVC = !"h264".equalsIgnoreCase(System.getProperty("cadence.replay.codec", "hevc"));
	private static final int WIDTH = HEVC ? 1920 : 1280;
	/** The replay kept was opened for HDR frames (10-bit HLG): HDR turning on or off starts it again. */
	private static boolean openedHdr;
	private static String codecName = "";
	private static MethodHandle OPEN, CAPTURE, SAVE, CLOSE;
	private static volatile boolean wanted;
	private static long replay, params;
	private static int outW, outH, srcW, srcH, gridW, gridH;
	private static long lastCapture;
	private static volatile boolean saving;
	private static boolean failed;
	private static int misses;

	private Replay() {
	}

	/** Video Settings: keep the last 30 s (on) or not (off: the ring and the encoder go). */
	public static void setEnabled(boolean on) {
		wanted = on;
	}

	public static boolean enabled() {
		return wanted && !failed;
	}

	/** The setting itself (the button toggles this: after a failure enabled() is false, and the player must still be able to turn it off). */
	public static boolean wanted() {
		return wanted;
	}

	/** MetalSurface.blitFromTexture (render thread): the frame about to be presented, GUI included. */
	public static void frame(Object encoder, GpuTextureView view) {
		if (!wanted) {
			if (replay != 0) closeLater();
			return;
		}
		if (failed) return;
		long now = System.nanoTime();
		if (now - lastCapture < 1_000_000_000L / FPS - 500_000L) return;
		int w = view.getWidth(0), h = view.getHeight(0);
		if (replay != 0 && openedHdr != dev.cadence.metal.backend.Hdr.active()) {
			closeLater();
			return;
		}
		if (replay == 0 && !open(encoder, w, h)) return;
		if (w != srcW || h != srcH) fit(w, h);
		lastCapture = now;
		try {
			int r = (int) CAPTURE.invokeExact(replay, MetalBridge.enc(encoder), MetalBridge.viewHandle(view), params, PARAMS_BYTES, gridW, gridH);
			// (one failed capture is skipped; only many in a row turn replay off for the session, BH4-17)
			misses = r == -1 ? misses + 1 : 0;
			if (misses >= 120) {
				failed = true;
				System.out.println("[cadence] instant replay: frames can't be read; replay off");
			}
		} catch (Throwable t) {
			failed = true;
			System.out.println("[cadence] instant replay failed: " + t);
		}
	}

	/** Saves the last 30 s to gameDir/replays (a background thread); done gets the line to show the player. */
	public static void save(Path gameDir, Consumer<String> done) {
		if (replay == 0 || saving) {
			done.accept(replay == 0 ? "Instant Replay is off (Video Settings > Instant Replay)" : "Still saving the last replay");
			return;
		}
		saving = true;
		long handle = replay;
		Thread t = new Thread(() -> {
			try {
				Path dir = gameDir.resolve("replays");
				Files.createDirectories(dir);
				Path file = dir.resolve("replay-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.SSS")) + ".mp4");
				long path = MemoryUtil.nmemAlloc(file.toString().length() * 3L + 1), err = MemoryUtil.nmemAlloc(512);
				try {
					MemoryUtil.memUTF8(file.toString(), true, MemoryUtil.memByteBuffer(path, file.toString().length() * 3 + 1));
					double seconds = (double) SAVE.invokeExact(handle, path, err, 512);
					done.accept(seconds < 0 ? "Replay not saved: " + MemoryUtil.memUTF8(err)
						: String.format(java.util.Locale.ROOT, "Replay saved: %s (%.0f s)", file.getFileName(), seconds));
				} finally {
					MemoryUtil.nmemFree(path);
					MemoryUtil.nmemFree(err);
				}
			} catch (Throwable e) {
				done.accept("Replay not saved: " + e.getMessage());
			} finally {
				saving = false;
			}
		}, "cadence-replay-save");
		t.setDaemon(true);
		t.start();
	}

	private static boolean open(Object encoder, int w, int h) {
		try {
			if (OPEN == null) {
				OPEN = fn("mcrp_open", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_DOUBLE, JAVA_INT, JAVA_LONG, JAVA_INT);
				CAPTURE = fn("mcrp_capture", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
				SAVE = fn("mcrp_save", JAVA_DOUBLE, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
				CLOSE = fn("mcrp_close", null, JAVA_LONG);
			}
			String src;
			try (InputStream in = Replay.class.getResourceAsStream("/cadence/rec/rec.metal")) {
				src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			// the video: 1920 wide (HEVC; 1280 for H.264), no wider than the frame, the window's aspect, even sizes
			outW = Math.max(2, Math.min(WIDTH, w) & ~1);
			outH = Math.max(2, (int) Math.round((double) outW * h / Math.max(1, w)) & ~1);
			boolean hdr = dev.cadence.metal.backend.Hdr.active();
			int codec = hdr ? 2 : HEVC ? 1 : 0;
			long s = MemoryUtil.nmemAlloc(src.length() * 3L + 1), err = MemoryUtil.nmemAlloc(512);
			try {
				MemoryUtil.memUTF8(src, true, MemoryUtil.memByteBuffer(s, src.length() * 3 + 1));
				replay = (long) OPEN.invokeExact(MetalBridge.ctx(encoder), s, outW, outH, FPS, hdr ? 12_000_000L : 8_000_000L, (double) SECONDS, codec, err, 512);
				if (replay == 0 && codec != 0) {
					// (no HEVC encoder: H.264 as before)
					System.out.println("[cadence] instant replay: " + MemoryUtil.memUTF8(err) + "; H.264 instead");
					outW = Math.max(2, Math.min(1280, w) & ~1);
					outH = Math.max(2, (int) Math.round((double) outW * h / Math.max(1, w)) & ~1);
					codec = 0;
					replay = (long) OPEN.invokeExact(MetalBridge.ctx(encoder), s, outW, outH, FPS, 8_000_000L, (double) SECONDS, codec, err, 512);
				}
				if (replay == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
				openedHdr = hdr;
				codecName = codec == 2 ? "HEVC 10-bit HDR (HLG)" : codec == 1 ? "HEVC" : "H.264";
			} finally {
				MemoryUtil.nmemFree(s);
				MemoryUtil.nmemFree(err);
			}
			params = MemoryUtil.nmemCalloc(1, PARAMS_BYTES);
			srcW = srcH = 0;
			System.out.println("[cadence] instant replay on: the last " + SECONDS + " s at " + outW + "x" + outH + ", " + codecName + ", hardware encoder");
			return true;
		} catch (Throwable t) {
			failed = true;
			System.out.println("[cadence] instant replay unavailable: " + t);
			return false;
		}
	}

	/**
	 * The source of w x h fitted into the video (rec.metal RecParams): the video keeps the aspect the window had when replay
	 * started; a window of another aspect since is fitted inside it with black bars (it was stretched, BH4-10).
	 */
	private static void fit(int w, int h) {
		srcW = w;
		srcH = h;
		int rw = outW, rh = outH;
		if ((long) w * outH > (long) h * outW) rh = Math.min(outH, (int) Math.round((double) outW * h / w) & ~1);
		else rw = Math.min(outW, (int) Math.round((double) outH * w / h) & ~1);
		int rx = ((outW - rw) / 2) & ~1, ry = ((outH - rh) / 2) & ~1;
		int tapsX = Math.clamp((int) Math.ceil((double) w / rw / 2 - 0.01), 1, 4), tapsY = Math.clamp((int) Math.ceil((double) h / rh / 2 - 0.01), 1, 4);
		int[] v = {outW, outH, rx, ry, rw, rh, tapsX, tapsY, outW * outH, 0, 0, 0};
		for (int i = 0; i < v.length; i++) MemoryUtil.memPutInt(params + 4L * i, v[i]);
		float[] crop = {0, 0, 1, 1};
		for (int i = 0; i < 4; i++) MemoryUtil.memPutFloat(params + 48 + 4L * i, crop[i]);
		gridW = outW / 2;
		gridH = outH / 2;
	}

	/** Turned off: the encoder and the ring let go on a background thread (it waits for the GPU's last captures). */
	private static void closeLater() {
		long handle = replay, p = params;
		replay = 0;
		params = 0;
		Thread t = new Thread(() -> {
			try {
				while (saving) Thread.sleep(50);  // (a save still reading the ring)
				CLOSE.invokeExact(handle);
			} catch (Throwable e) {
				System.out.println("[cadence] instant replay: closing failed: " + e);
			}
			MemoryUtil.nmemFree(p);
		}, "cadence-replay-close");
		t.setDaemon(true);
		t.start();
	}

	private static MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return LINKER.downcallHandle(symbol, d);
	}
}
