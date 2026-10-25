package dev.cadence.metal.rec;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.cadence.metal.MetalBridge;
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
 * as hardware-encoded H.264 (rec.m mcrp_*: the recorder's GPU conversion into an IOSurface pixel buffer, VideoToolbox, a ring of
 * encoded frames, about 30 MB at 1280 wide), saved on request to replays/replay-DATE.mp4 by AVFoundation (nothing re-encoded).
 * At most 60 frames a second are kept, each at the time it was shown; no sound. Off by default: on, it costs a GPU dispatch
 * per kept frame (~0.1 ms) and the Mac's media encoder.
 */
public final class Replay {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final int PARAMS_BYTES = 64, SECONDS = 30, WIDTH = 1280, FPS = 60;
	private static MethodHandle OPEN, CAPTURE, SAVE, CLOSE;
	private static volatile boolean wanted;
	private static long replay, params;
	private static int outW, outH, srcW, srcH, gridW, gridH;
	private static long lastCapture;
	private static volatile boolean saving;
	private static boolean failed;

	private Replay() {
	}

	/** Video Settings: keep the last 30 s (on) or not (off: the ring and the encoder go). */
	public static void setEnabled(boolean on) {
		wanted = on;
	}

	public static boolean enabled() {
		return wanted && !failed;
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
		if (replay == 0 && !open(encoder, w, h)) return;
		if (w != srcW || h != srcH) fit(w, h);
		lastCapture = now;
		try {
			int r = (int) CAPTURE.invokeExact(replay, MetalBridge.enc(encoder), MetalBridge.viewHandle(view), params, PARAMS_BYTES, gridW, gridH);
			if (r == -1) {
				failed = true;
				System.out.println("[cadence] instant replay: this frame can't be read; replay off");
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
				Path file = dir.resolve("replay-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".mp4");
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
				OPEN = fn("mcrp_open", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_DOUBLE, JAVA_LONG, JAVA_INT);
				CAPTURE = fn("mcrp_capture", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
				SAVE = fn("mcrp_save", JAVA_DOUBLE, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
				CLOSE = fn("mcrp_close", null, JAVA_LONG);
			}
			String src;
			try (InputStream in = Replay.class.getResourceAsStream("/cadence/rec/rec.metal")) {
				src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			// the video: 1280 wide, the window's aspect, even sizes
			outW = WIDTH;
			outH = Math.max(2, (int) Math.round((double) WIDTH * h / Math.max(1, w)) & ~1);
			long s = MemoryUtil.nmemAlloc(src.length() * 3L + 1), err = MemoryUtil.nmemAlloc(512);
			try {
				MemoryUtil.memUTF8(src, true, MemoryUtil.memByteBuffer(s, src.length() * 3 + 1));
				replay = (long) OPEN.invokeExact(MetalBridge.ctx(encoder), s, outW, outH, FPS, 8_000_000L, (double) SECONDS, err, 512);
				if (replay == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			} finally {
				MemoryUtil.nmemFree(s);
				MemoryUtil.nmemFree(err);
			}
			params = MemoryUtil.nmemCalloc(1, PARAMS_BYTES);
			srcW = srcH = 0;
			System.out.println("[cadence] instant replay on: the last " + SECONDS + " s at " + outW + "x" + outH);
			return true;
		} catch (Throwable t) {
			failed = true;
			System.out.println("[cadence] instant replay unavailable: " + t);
			return false;
		}
	}

	/** The source of w x h fitted into the video (rec.metal RecParams; the window's aspect: no bars). */
	private static void fit(int w, int h) {
		srcW = w;
		srcH = h;
		int tapsX = Math.clamp((int) Math.ceil((double) w / outW / 2 - 0.01), 1, 4), tapsY = Math.clamp((int) Math.ceil((double) h / outH / 2 - 0.01), 1, 4);
		int[] v = {outW, outH, 0, 0, outW, outH, tapsX, tapsY, outW * outH, 0, 0, 0};
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
