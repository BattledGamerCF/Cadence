package dev.cadence.metal.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.cadence.metal.MetalBridge;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * Ray Tracing (Video Settings > Cadence: Apple Hardware > Ray Tracing): shadows from the sun and the moon, traced per pixel
 * through a voxel copy of the world near the camera (RtWorld, rt.metal) instead of a shadow map, after the world is drawn and
 * before the hand and the HUD. In shadow, a pixel gets the light the game's own lightmap gives for fewer sky light levels there,
 * so torch-lit places stay torch-lit and nights get soft moon shadows. Surfaces facing away from the light count as shadowed.
 *
 * <p>Two tracers, the same picture: Hardware (Metal ray tracing through an acceleration structure of the world's 4 x 4 x 4
 * bricks; in hardware on M3 and newer, in software before) and Voxel (a brick-skipping walk through the voxels in a compute
 * kernel, fastest on M1 and M2). Auto picks Hardware on M3 and newer. Not with a shader pack (it has its own shadows), with World
 * Resolution below 100% (the world's depth isn't the main target's then), or in Render Max FPS. -Dcadence.rt=false: never.
 */
public final class RayTracing {
	private static final boolean ALLOWED = !"false".equals(System.getProperty("cadence.rt"));
	/** -Dcadence.rt.window: the voxel window's width in blocks (64, 128 or 256; default 128: ±64 blocks, 12 MB). */
	private static final int WINDOW = Integer.highestOneBit(Math.clamp(Integer.getInteger("cadence.rt.window", 128), 64, 256));
	/** -Dcadence.rt.debug=true: the shadow visibility drawn instead of the frame (white lit, black in shadow). */
	private static final boolean DEBUG = Boolean.getBoolean("cadence.rt.debug");
	/** -Dcadence.rt.budgetMs: render-thread time per frame for writing the voxel window (default 2 ms). */
	private static final long BUDGET_NS = (long) (Double.parseDouble(System.getProperty("cadence.rt.budgetMs", "2")) * 1e6);
	private static final int FRAME_BYTES = 256;
	/** -Dcadence.rt.scale=1|2: a shadow ray per pixel, or per 2 x 2 pixels upsampled by depth (default 2: a quarter of the cost). */
	/** -Dcadence.rt.strength: the sky light levels a full sun shadow takes away (default 10 of 15; the game's lightmap gives the rest). */
	private static final float STRENGTH = Float.parseFloat(System.getProperty("cadence.rt.strength", "10"));
	private static final int SCALE = Math.clamp(Integer.getInteger("cadence.rt.scale", 2), 1, 2);

	public enum Mode {
		OFF("Off"), SHADOWS("Shadows");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	public enum Tracer {
		AUTO("Auto"), HARDWARE("Hardware"), VOXEL("Voxel");

		public final String label;

		Tracer(String label) {
			this.label = label;
		}
	}

	private static volatile Mode mode = Mode.OFF;
	private static volatile Tracer tracer = Tracer.AUTO;
	private static RtWorld world;
	private static long rt, ctx, enc, frameBuf;
	private static boolean failed, rtHw;
	private static int support = -1;
	private static long frames;
	// this level frame's camera and projection, and the sky's state
	private static final Matrix4f proj = new Matrix4f(), viewRot = new Matrix4f(), viewProj = new Matrix4f(), inv = new Matrix4f(), prevViewProj = new Matrix4f();
	private static double camX, camY, camZ;
	private static float sunAngle, moonAngle, rain;
	private static boolean usedHw;
	private static double gpuEstimateMs;

	private RayTracing() {
	}

	public static Mode mode() {
		return mode;
	}

	public static void setMode(Mode m) {
		mode = ALLOWED ? m : Mode.OFF;
	}

	public static Tracer tracer() {
		return tracer;
	}

	public static void setTracer(Tracer t) {
		if (t == tracer) return;
		tracer = t;
		stale = true;  // (the acceleration structure is made with the world: made again on the render thread)
	}

	private static volatile boolean stale;

	/** 2: hardware ray tracing (M3 and newer), 1: Metal's ray tracing in software, 0: none; -1 not known yet (no frame yet). */
	public static int support() {
		return support;
	}

	/** Whether the last frame's shadows went through the acceleration structure. */
	public static boolean usedHardware() {
		return usedHw;
	}

	/** Voxel window columns/sections still to write (0: complete). */
	public static int backlog() {
		RtWorld w = world;
		return w == null ? 0 : w.backlog();
	}

	// ---- the frame's inputs (mixins in dev.cadence.metal.mixin.rt) ----

	public static void camera(double x, double y, double z, Matrix4fc rotation) {
		camX = x;
		camY = y;
		camZ = z;
		viewRot.set(rotation);
	}

	public static void projection(Matrix4fc projection) {
		proj.set(projection);
	}

	public static void sky(float sun, float moon, float rainLevel) {
		sunAngle = sun;
		moonAngle = moon;
		rain = rainLevel;
	}

	public static void sectionDirty(int x, int y, int z) {
		RtWorld w = world;
		if (w != null) w.sectionDirty(x, y, z);
	}

	// ---- the frame ----

	/**
	 * GameRenderer.renderLevel, right before the hand and the HUD (render thread; the world is in color and depth): the shadows
	 * traced and applied. pack: a shader pack is on; scaled: the world was drawn below 100% (nothing done in either case).
	 */
	public static void afterWorld(GpuTexture color, GpuTexture depth, boolean pack, boolean scaled) {
		if (mode == Mode.OFF || failed || pack || scaled || color == null || depth == null || dev.cadence.metal.record.Record.active()) {
			if (mode == Mode.OFF && world != null) dropWorld();
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;
		// (a world for another level or tracer goes first: dropping a hardware one lets go of its native state, made again below)
		if (world != null && (world.level() != level || stale)) dropWorld();
		stale = false;
		if (!init()) return;
		if (world == null) {
			boolean hw = tracer == Tracer.HARDWARE || tracer == Tracer.AUTO && support == 2;
			world = new RtWorld(ctx, level, WINDOW);
			if (hw) {
				if (rt != 0) RtNative.free(rt);
				rt = createRt(true);
				rtHw = rt != 0 && RtNative.hw(rt);
				world.rt = rt;
				world.hw = rtHw;
			}
			System.out.printf(java.util.Locale.ROOT, "[cadence] ray tracing: %dx%dx%d voxel window (%.1f MB), %s tracer%n", world.w, world.h, world.w,
				world.bytes() / 1048576.0, rtHw ? "hardware (" + (support == 2 ? "M3+ ray tracing hardware" : "Metal's software traversal") + ")" : "voxel");
		}
		world.follow(camX, camZ);
		world.work(BUDGET_NS);
		writeFrame(level, color.getWidth(0), color.getHeight(0));
		var view = mc.gameRenderer.lightmap();
		long light = view == null ? 0 : MetalBridge.textureHandle(view.texture());
		int flags = (rtHw ? 1 : 0) | (DEBUG ? 2 : 0) | SCALE << 8;
		int r = RtNative.frame(rt, enc, MetalBridge.textureHandle(color), MetalBridge.textureHandle(depth), light, world.vox, world.bricks, world.sections,
			frameBuf, FRAME_BYTES, world.ox >> 4, world.oz >> 4, flags);
		usedHw = r == 1;
		prevViewProj.set(viewProj);
		frames++;
	}

	private static void writeFrame(ClientLevel level, int width, int height) {
		viewProj.set(proj).mul(viewRot);
		inv.set(viewProj).invert();
		long b = frameBuf;
		inv.get(MemoryUtil.memByteBuffer(b, 64));
		prevViewProj.get(MemoryUtil.memByteBuffer(b + 64, 64));
		RtWorld w = world;
		// the window's cell (0, 0, 0) relative to the camera
		MemoryUtil.memPutFloat(b + 128, (float) (w.ox - camX));
		MemoryUtil.memPutFloat(b + 132, (float) (w.minY - camY));
		MemoryUtil.memPutFloat(b + 136, (float) (w.oz - camZ));
		MemoryUtil.memPutFloat(b + 140, w.w * 0.5f);
		// the light: the sun by day, the moon by night (SkyRenderer turns them about x after -90 degrees about y)
		float sx = (float) -Math.sin(sunAngle), sy = (float) Math.cos(sunAngle);
		float mx = (float) -Math.sin(moonAngle), my = (float) Math.cos(moonAngle);
		float day = smooth(-0.05f, 0.2f, sy), night = smooth(-0.05f, 0.2f, my) * (1 - day);
		float clear = 1 - 0.85f * rain;
		boolean sun = day >= night;
		boolean hasSky = level.dimensionType().hasSkyLight();
		MemoryUtil.memPutFloat(b + 144, sun ? sx : mx);
		MemoryUtil.memPutFloat(b + 148, sun ? sy : my);
		MemoryUtil.memPutFloat(b + 152, 0);
		MemoryUtil.memPutFloat(b + 156, hasSky ? (sun ? day : 0.5f * night) * clear : 0);
		MemoryUtil.memPutInt(b + 160, w.w);
		MemoryUtil.memPutInt(b + 164, w.h);
		MemoryUtil.memPutInt(b + 168, w.ox & (w.w - 1));
		MemoryUtil.memPutInt(b + 172, w.oz & (w.w - 1));
		MemoryUtil.memPutFloat(b + 176, 96f);           // longest shadow ray
		MemoryUtil.memPutFloat(b + 180, (float) (frames & 0xFFFF));
		MemoryUtil.memPutFloat(b + 184, STRENGTH);      // sky light levels a full shadow removes
		MemoryUtil.memPutFloat(b + 188, 0f);            // soft shadow radius (hard)
		MemoryUtil.memPutInt(b + 192, width);
		MemoryUtil.memPutInt(b + 196, height);
		MemoryUtil.memPutInt(b + 200, DEBUG ? 1 : 0);
		MemoryUtil.memPutInt(b + 204, 0);
		MemoryUtil.memPutFloat(b + 224, Math.max(w.top, 1));  // extra.x: the window's top
		MemoryUtil.memPutFloat(b + 228, SCALE);
	}

	private static float smooth(float a, float b, float x) {
		float t = Math.clamp((x - a) / (b - a), 0f, 1f);
		return t * t * (3 - 2 * t);
	}

	private static boolean init() {
		if (rt != 0) return true;
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			ctx = MetalBridge.ctx(encoder);
			enc = MetalBridge.enc(encoder);
			support = RtNative.support(ctx);
			if (frameBuf == 0) frameBuf = MemoryUtil.nmemCalloc(1, FRAME_BYTES);
			rt = createRt(false);
			if (rt == 0) {
				failed = true;
				return false;
			}
			System.out.println("[cadence] ray tracing: " + (support == 2 ? "hardware ray tracing (Apple GPU family 9)" : support == 1
				? "Metal ray tracing in software (no ray tracing hardware on this chip)" : "no Metal ray tracing") + "; voxel tracer ready");
			return true;
		} catch (Throwable t) {
			failed = true;
			System.out.println("[cadence] ray tracing unavailable: " + t);
			return false;
		}
	}

	private static long createRt(boolean hw) {
		String src;
		try (InputStream in = RayTracing.class.getResourceAsStream("/cadence/rt/rt.metal")) {
			src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			System.out.println("[cadence] ray tracing: rt.metal missing: " + e);
			return 0;
		}
		var source = MemoryUtil.memUTF8(src);
		long err = MemoryUtil.nmemCalloc(1, 2048);
		try {
			long h = RtNative.create(ctx, MemoryUtil.memAddress(source), hw, WINDOW, Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getHeight() : 384,
				err, 2048);
			if (h == 0) System.out.println("[cadence] ray tracing: " + MemoryUtil.memUTF8(err));
			return h;
		} finally {
			MemoryUtil.memFree(source);
			MemoryUtil.nmemFree(err);
		}
	}

	/** The voxel window let go (Ray Tracing off, another world, the tracer changed); made again at the next use. */
	private static void dropWorld() {
		RtWorld w = world;
		world = null;
		if (w == null) return;
		// (the GPU may still read the buffers: released after the frame in flight; one second is far more than enough)
		Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException ignored) {
			}
			Minecraft.getInstance().execute(w::release);
		});
		if (rtHw && rt != 0) {
			long old = rt;
			rt = 0;
			rtHw = false;
			Thread.ofVirtual().start(() -> {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException ignored) {
				}
				Minecraft.getInstance().execute(() -> RtNative.free(old));
			});
		}
	}
}
