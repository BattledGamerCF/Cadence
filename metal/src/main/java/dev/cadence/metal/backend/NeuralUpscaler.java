package dev.cadence.metal.backend;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * The Neural Engine upscaler (World Resolution's Upscaler: Neural Engine): the world drawn at a lower resolution, with or without
 * a shader pack, upscaled by VideoToolbox's low-latency super resolution on the Neural Engine (mcneural.m mcnu_*) instead of
 * MetalFX on the GPU. The scaler offers 2x and 1.5x and takes at most 1280 x 1280 (2x only up to about 960 wide), so a larger
 * picture is upscaled in 2 or 4 overlapping tiles: a fullscreen Retina world at 50% (1470 x 923) is two tiles of 768 x 924,
 * 2.2 ms each on an M2's Neural Engine, beside the GPU. The world on screen is the one from the frame before (the model runs
 * while the GPU draws the next); the hand and the HUD are always the frame's own.
 *
 * <p>Starting the scaler for a size takes about a second the first time (Apple's model is compiled), so it starts on another
 * thread while MetalFX upscales; the last two sizes' scalers are kept (Auto resolution moves between neighbouring sizes).
 * Below a 1.3x enlargement (World Resolution above ~77%) MetalFX upscales: the scaler's smallest step is 1.5x. Not for an HDR
 * frame (the 8-bit model would clip its highlights). Render thread only, except the starts.
 */
public final class NeuralUpscaler {
	private static final Linker LINKER = Linker.nativeLinker();
	private static MethodHandle NEW, FRAME, FREE, TILES;
	/** Scalers by size ("WxH@scale"), the most recently used last; at most KEEP. */
	private static final Map<String, Long> READY = new LinkedHashMap<>();
	private static final int KEEP = 2;
	private static volatile String starting = "";
	private static volatile long started;
	private static volatile String startedFor = "";
	private static final java.util.Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private NeuralUpscaler() {
	}

	/** Upscales source into destination on the current frame (from the model's last picture); false: not this way (none yet, or unsupported). */
	public static boolean encode(GpuTextureView source, GpuTextureView destination) {
		if (!(source instanceof MetalTexture.View in) || !(destination instanceof MetalTexture.View out)) return false;
		var device = RenderSystem.getDevice();
		if (device == null || !(((FrontendCommandEncoder) device.createCommandEncoder()).backend() instanceof MetalEncoder encoder)) return false;
		int sw = source.getWidth(0), sh = source.getHeight(0), dw = destination.getWidth(0);
		float ratio = (float) dw / sw;
		if (ratio < 1.3f) return false;
		float scale = ratio > 1.75f ? 2f : 1.5f;
		String key = sw + "x" + sh + "@" + scale;
		if (FAILED.contains(key)) return false;
		try {
			link();
			long ready = started;
			if (ready != 0) {
				started = 0;
				keep(startedFor, ready);
			}
			Long handle = READY.get(key);
			if (handle == null) {
				if (starting.isEmpty()) start(encoder.ctx, key, sw, sh, scale);
				return false;
			}
			READY.remove(key);
			READY.put(key, handle);  // (most recently used last)
			encoder.flushClear((MetalTexture) source.texture());
			encoder.dropPendingClear((MetalTexture) destination.texture());
			return (int) FRAME.invokeExact((long) handle, encoder.enc, in.handle, out.handle) == 1;
		} catch (Throwable t) {
			FAILED.add(key);
			System.out.println("[cadence] neural upscaler failed: " + t);
			return false;
		}
	}

	private static void link() {
		if (NEW != null) return;
		NEW = fn("mcnu_new", JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_LONG, JAVA_INT);
		FRAME = fn("mcnu_frame", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
		FREE = fn("mcnu_free", null, JAVA_LONG);
		TILES = fn("mcnu_tiles", null, JAVA_LONG, JAVA_LONG);
	}

	/** The scaler for a size started on another thread (MetalFX upscales until it's ready). */
	private static void start(long ctx, String key, int sw, int sh, float scale) {
		starting = key;
		Thread.ofVirtual().name("cadence-neural-upscaler").start(() -> {
			long err = MemoryUtil.nmemCalloc(1, 512), tiles = MemoryUtil.nmemAlloc(8);
			try {
				long t0 = System.nanoTime();
				long h = (long) NEW.invokeExact(ctx, sw, sh, scale, err, 512);
				if (h == 0) {
					FAILED.add(key);
					System.out.println("[cadence] neural upscaler: " + MemoryUtil.memUTF8(err) + "; MetalFX instead");
				} else {
					TILES.invokeExact(h, tiles);
					int tx = MemoryUtil.memGetInt(tiles), ty = MemoryUtil.memGetInt(tiles + 4);
					System.out.println(String.format(java.util.Locale.ROOT, "[cadence] neural upscaler: %dx%d upscaled %.1fx on the Neural Engine%s (ready in %.0f ms)",
						sw, sh, scale, tx * ty > 1 ? " in " + tx + " x " + ty + " tiles" : "", (System.nanoTime() - t0) / 1e6));
					startedFor = key;
					started = h;
				}
			} catch (Throwable t) {
				FAILED.add(key);
				System.out.println("[cadence] neural upscaler failed: " + t);
			} finally {
				MemoryUtil.nmemFree(err);
				MemoryUtil.nmemFree(tiles);
				starting = "";
			}
		});
	}

	private static void keep(String key, long handle) {
		Long old = READY.put(key, handle);
		if (old != null) free(old);
		while (READY.size() > KEEP) {
			var eldest = READY.entrySet().iterator().next();
			READY.remove(eldest.getKey());
			free(eldest.getValue());
		}
	}

	/** Let go of every scaler (the upscaler changed, or the world closed): a second later, off the render thread (frames in flight may use them). */
	public static void release() {
		long ready = started;
		started = 0;
		if (ready != 0) free(ready);
		if (READY.isEmpty()) return;
		for (long h : READY.values()) free(h);
		READY.clear();
	}

	private static void free(long h) {
		if (h == 0) return;
		Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(1000);
				FREE.invokeExact(h);
			} catch (Throwable ignored) {
			}
		});
	}

	private static MethodHandle fn(String name, java.lang.foreign.MemoryLayout result, java.lang.foreign.MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		return LINKER.downcallHandle(MetalBridge.library().find(name).orElseThrow(), d);
	}
}
