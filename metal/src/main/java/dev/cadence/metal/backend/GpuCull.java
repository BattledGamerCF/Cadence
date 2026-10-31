package dev.cadence.metal.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * Cadence: the two-phase occlusion culling kernels (native/mccull.m) for callers outside this package: phase-1 setup of a
 * run of indirect draw commands, the depth pyramid, the phase-2 test. Each call encodes compute work on the current frame
 * (ending any open encoder). false: not available (draw normally). Render thread only.
 */
public final class GpuCull {
	private static long state;
	private static boolean failed;
	private static final long MATRIX = MemoryUtil.nmemAlloc(64);

	private GpuCull() {}

	private static MetalEncoder encoder() {
		var device = RenderSystem.getDevice();
		if (device == null || !(((FrontendCommandEncoder) device.createCommandEncoder()).backend() instanceof MetalEncoder e)) return null;
		return e;
	}

	/** The kernels are built (first call builds them). */
	public static boolean available() {
		if (failed) return false;
		if (state != 0) return true;
		MetalEncoder e = encoder();
		if (e == null) return false;
		state = Native.cullNew(e.ctx);
		if (state == 0) {
			failed = true;
			System.out.println("cadence-metal: Cadence occlusion culling unavailable (kernels didn't build)");
		}
		return state != 0;
	}

	/** This frame's projection*view, camera block position and fraction, and frame number (>= 2, +1 per frame). */
	public static void params(Matrix4fc viewProjection, int camX, int camY, int camZ, float fracX, float fracY, float fracZ, int frame) {
		params(viewProjection, camX, camY, camZ, fracX, fracY, fracZ, frame, 0);
	}

	/** Depth conventions for {@code mode}: bit 0 reversed depth (near 1, far 0), bit 1 clip-space z already 0..1. */
	public static final int REVERSED = 1, ZERO_TO_ONE = 2;

	public static void params(Matrix4fc viewProjection, int camX, int camY, int camZ, float fracX, float fracY, float fracZ, int frame, int mode) {
		if (!available()) return;
		for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(MATRIX + i * 4L, viewProjection.get(i / 4, i % 4));
		Native.cullParams(state, MATRIX, camX, camY, camZ, fracX, fracY, fracZ, frame, mode);
	}

	// ---- the vanilla terrain pass (no shader pack): phase 1 set up before the frame, one split of the open pass ----

	/** The open render pass can be split and reopened exactly (checked before phase 1 is set up for it). */
	public static boolean canSplit() {
		MetalEncoder e = encoder();
		return available() && e != null && Native.cullSplitOk(e.enc) != 0;
	}

	/** Phase 1 setup in the pre command buffer (runs before this frame's passes). */
	public static boolean phaseAPre(GpuBufferSlice draws, int count, GpuBufferSlice infos) {
		MetalEncoder e = encoder();
		if (!available() || e == null || !(draws.buffer() instanceof MetalBuffer d) || !(infos.buffer() instanceof MetalBuffer i)) return false;
		Native.cullAPre(e.enc, state, d.handle, draws.offset(), count, i.handle, infos.offset());
		return true;
	}

	/** Ends the open pass and builds the pyramid of its depth; the returned handle takes phaseBOn calls, then resume. 0: not split. */
	public static long split() {
		MetalEncoder e = encoder();
		if (!available() || e == null) return 0;
		return Native.cullVSplit(e.enc, e.ctx, state);
	}

	public static boolean phaseBOn(long split, GpuBufferSlice draws, int count, GpuBufferSlice infos) {
		if (split == 0 || !(draws.buffer() instanceof MetalBuffer d) || !(infos.buffer() instanceof MetalBuffer i)) return false;
		Native.cullBOn(split, state, d.handle, draws.offset(), count, i.handle, infos.offset());
		return true;
	}

	/** Reopens the pass split by {@link #split} on the same attachments (contents loaded back) and binds its state again. */
	public static void resume(long split) {
		MetalEncoder e = encoder();
		if (split == 0 || e == null) return;
		Native.cullVResume(e.enc, split);
		e.restorePass();
	}

	public static boolean phaseA(GpuBufferSlice draws, int count, GpuBufferSlice infos) {
		MetalEncoder e = encoder();
		if (!available() || e == null || !(draws.buffer() instanceof MetalBuffer d) || !(infos.buffer() instanceof MetalBuffer i)) return false;
		Native.cullA(e.enc, state, d.handle, draws.offset(), count, i.handle, infos.offset());
		return true;
	}

	public static boolean pyramid(GpuTexture depth) {
		MetalEncoder e = encoder();
		if (!available() || e == null || !(depth instanceof MetalTexture t)) return false;
		Native.cullPyramid(e.enc, e.ctx, state, t.handle);
		return true;
	}

	/** No culling after all this frame: the commands phase 1 skipped are drawn, the ones it drew aren't. */
	public static boolean rest(GpuBufferSlice draws, int count) {
		MetalEncoder e = encoder();
		if (!available() || e == null || !(draws.buffer() instanceof MetalBuffer d)) return false;
		Native.cullRest(e.enc, state, d.handle, draws.offset(), count);
		return true;
	}

	/** Translucent commands after the opaque terrain: instance count = not behind this frame's pyramid. */
	public static boolean translucent(GpuBufferSlice draws, int count, GpuBufferSlice infos) {
		MetalEncoder e = encoder();
		if (!available() || e == null || !(draws.buffer() instanceof MetalBuffer d) || !(infos.buffer() instanceof MetalBuffer i)) return false;
		Native.cullT(e.enc, state, d.handle, draws.offset(), count, i.handle, infos.offset());
		return true;
	}

	public static boolean phaseB(GpuBufferSlice draws, int count, GpuBufferSlice infos) {
		MetalEncoder e = encoder();
		if (!available() || e == null || !(draws.buffer() instanceof MetalBuffer d) || !(infos.buffer() instanceof MetalBuffer i)) return false;
		Native.cullB(e.enc, state, d.handle, draws.offset(), count, i.handle, infos.offset());
		return true;
	}
}
