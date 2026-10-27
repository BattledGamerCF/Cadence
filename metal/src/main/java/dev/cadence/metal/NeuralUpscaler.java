package dev.cadence.metal;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import org.lwjgl.system.MemoryUtil;

/**
 * The Neural Engine upscaler (World Resolution's Upscaler: Neural Engine): the world drawn at a lower resolution upscaled by
 * VideoToolbox's low-latency super resolution on the Neural Engine (mcneural.m mcnu_*) instead of MetalFX on the GPU. It offers 2x
 * and 1.5x for pictures up to 1280 wide (World Resolution 50% or 67% of a window up to about 1920 wide; Retina-sized windows
 * use MetalFX). The world on screen is the one from the frame before (the model runs while the GPU draws the next); the hand
 * and the HUD are always the frame's own. Render thread only.
 */
public final class NeuralUpscaler {
	private static final Linker LINKER = Linker.nativeLinker();
	private static MethodHandle NEW, FRAME, FREE;
	private static long handle;
	private static int inW, inH;
	private static String failedFor = "";

	private NeuralUpscaler() {
	}

	/** Upscales source into destination on the current frame (from the model's last picture); false: not this way (none yet, or unsupported). */
	public static boolean encode(GpuTextureView source, GpuTextureView destination) {
		if (!(source instanceof MetalTexture.View in) || !(destination instanceof MetalTexture.View out)) return false;
		var device = RenderSystem.getDevice();
		if (device == null || !(((FrontendCommandEncoder) device.createCommandEncoder()).backend() instanceof MetalEncoder encoder)) return false;
		int sw = source.getWidth(0), sh = source.getHeight(0), dw = destination.getWidth(0);
		float ratio = (float) dw / sw, scale = ratio > 1.75f ? 2f : 1.5f;
		String key = sw + "x" + sh + "@" + scale;
		if (key.equals(failedFor)) return false;
		try {
			if (handle == 0 || sw != inW || sh != inH) {
				if (NEW == null) {
					NEW = fn("mcnu_new", JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_LONG, JAVA_INT);
					FRAME = fn("mcnu_frame", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
					FREE = fn("mcnu_free", null, JAVA_LONG);
				}
				release();
				long err = MemoryUtil.nmemCalloc(1, 512);
				try {
					handle = (long) NEW.invokeExact(encoder.ctx, sw, sh, scale, err, 512);
					if (handle == 0) {
						failedFor = key;
						System.out.println("[cadence] neural upscaler: " + MemoryUtil.memUTF8(err) + "; MetalFX instead");
						return false;
					}
				} finally {
					MemoryUtil.nmemFree(err);
				}
				inW = sw;
				inH = sh;
				System.out.println("[cadence] neural upscaler: " + sw + "x" + sh + " upscaled " + scale + "x on the Neural Engine");
			}
			encoder.flushClear((MetalTexture) source.texture());
			encoder.dropPendingClear((MetalTexture) destination.texture());
			return (int) FRAME.invokeExact(handle, encoder.enc, in.handle, out.handle) == 1;
		} catch (Throwable t) {
			failedFor = key;
			System.out.println("[cadence] neural upscaler failed: " + t);
			return false;
		}
	}

	/** Let go (another size, or the upscaler changed): a second later, off the render thread (frames in flight may use it). */
	public static void release() {
		long h = handle;
		handle = 0;
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
