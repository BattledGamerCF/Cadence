package dev.cadence.metal.framegen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.cadence.metal.MetalBridge;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;

/**
 * Frame Generation: twice the frames on screen for the frames rendered. Each real frame leaves a snapshot of the world (its
 * colour and each pixel's distance, before the hand and the GUI: {@link #snapshot}); between two real frames one more is made
 * from the snapshots by reprojection (framegen.metal) and presented, the hand and the GUI taken from the newest real frame as
 * they are. No motion vectors are needed (Minecraft and shader packs have none): the camera's own motion is reprojected,
 * moving things (mobs, particles) show where the newest frame has them.
 *
 * <ul>
 * <li>{@link Mode#SMOOTH}: the camera halfway between the last two frames, presented before the newest (with the frame,
 * MetalSurface.blitFromTexture); one frame of added latency.
 * <li>{@link Mode#RESPONSIVE}: the newest frame seen from the camera the mouse has turned to since, presented halfway to the
 * next frame (from the frame limiter's wait); no added latency, the mouse feels as fast as the frames shown.
 * <li>{@link Mode#NEURAL}: as Smooth, the frame halfway made by Apple's own frame interpolation (VideoToolbox's low-latency
 * interpolator, a neural network on the Neural Engine, beside the GPU) from the last two snapshots at up to 1280 x 720; until its
 * model has loaded (about a second), and where it can't run (before macOS 26), Smooth's reprojection.
 * </ul>
 *
 * It runs only under a frame cap below the display's rate (the shaders module's FramerateCapMixin decides: {@link #target}):
 * it renders at the cap and shows twice that, every present at least half the frame interval (FramePacing), so real and
 * generated frames alternate evenly. Not with Render Max FPS (it has its own) or the Metal 4 path.
 */
public final class FrameGen {
	public enum Mode {
		OFF("Off"), SMOOTH("Smooth"), RESPONSIVE("Responsive"), NEURAL("Neural");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	/** -Dcadence.fg.log=true: a line a second (frames generated, GPU time). */
	private static final boolean LOG = Boolean.getBoolean("cadence.fg.log");
	private static final int FRAME_BYTES = 3 * 144 + 32, SNAP_BYTES = 80;

	private static volatile Mode mode = Mode.OFF;
	/** This frame's rates while it runs: rendered and shown (0: not running). */
	private static volatile int rendered, shown;
	private static boolean failed;
	/** (the backend on Metal 4: no Frame Generation; read once) */
	private static int metal4 = -1;
	private static Object encoder;
	private static long ctx, enc, fg, frameBuf, snapBuf;

	// the cameras: this level frame's (camera(), projection()), and those of the last two snapshots (index: slot)
	private static final Matrix4f proj = new Matrix4f(), viewRot = new Matrix4f(), viewProj = new Matrix4f();
	private static double camX, camY, camZ;
	private static float yawAt, pitchAt;
	private static boolean cameraFresh;
	private static final Matrix4f[] slotProj = {new Matrix4f(), new Matrix4f()}, slotRot = {new Matrix4f(), new Matrix4f()};
	private static final double[][] slotPos = new double[2][3];
	private static final float[][] slotLook = new float[2][2];  // the player's yaw and pitch (getYRot/getXRot) at the snapshot
	private static final long[] slotFrame = {-10, -10};
	private static boolean floatSnap;
	/** When each snapshot was taken (nanoTime): an older one isn't the frame before (FG paused, a hitch, a menu). */
	private static final long[] slotTime = new long[2];
	private static volatile long frames;
	private static int slot = -1;
	private static int width, height;
	/** The newest real frame (the presented texture, GUI included), for the generated frames' hand and GUI. */
	private static long latest;
	/** Responsive: the frame limiter's wait is split at the middle and a frame made there, at most once per real frame. */
	private static long responsiveFor = -1;
	/** Presents in a row with no snapshot while running, and until when Frame Generation stands down after too many. */
	private static int presentsWithout;
	/** The snapshot whose frame was last presented (a frame presents once; a present with no new snapshot generates nothing). */
	private static long presented = -1;
	private static long idleUntil;
	private static long genCount, genReported, reportNanos;
	private static int genRate;

	private FrameGen() {
	}

	/** Video Settings > Frame Generation. */
	public static void setMode(Mode m) {
		mode = m == null ? Mode.OFF : m;
		if (mode == Mode.OFF) target(0, 0);
	}

	public static Mode mode() {
		return mode;
	}

	/**
	 * Battery Saver with a shader pack (BatterySaver): Responsive whatever the chosen mode. Smooth draws its frame in the
	 * real frame's work, and at Battery Saver's low clocks that pushed BSL's 30 rendered to 27-29 (56-58 shown); Responsive
	 * held a steady 30 (60 shown) for ~1 W more (10.9-11.3 W against 9.4-10.5) and adds no delay.
	 */
	public static volatile boolean saverResponsive;

	/** The mode in use: the chosen one, Responsive while Battery Saver asks for it. */
	public static Mode effective() {
		Mode m = mode;
		return m != Mode.OFF && saverResponsive ? Mode.RESPONSIVE : m;
	}

	/**
	 * The shaders module's frame cap, each time the game reads it: the rate rendered and the rate shown (0, 0: not running this
	 * frame). Running only when shown is above rendered.
	 */
	public static void target(int renderedRate, int shownRate) {
		long now = System.nanoTime();
		boolean wanted = effective() != Mode.OFF && !failed && HOOKS && renderedRate > 0 && shownRate > renderedRate
			&& (effective() != Mode.RESPONSIVE || dev.cadence.metal.FrameWait.ENABLED);
		// (the backend is up whenever this is asked for in a world: read once then)
		if (wanted && metal4 < 0) metal4 = MetalBridge.metal4() ? 1 : 0;
		// (no snapshots lately, World Resolution below 100% for one: not running for a few seconds, so the cap isn't halved for
		// nothing; then tried again)
		boolean on = wanted && metal4 == 0 && now >= idleUntil;
		rendered = on ? renderedRate : 0;
		shown = on ? shownRate : 0;
		// (off for 5 s: the snapshots' memory goes, 70-180 MB at Retina sizes; made again when it runs)
		if (on) lastRunning = now;
		else if (width != 0 && now - lastRunning > 5_000_000_000L) reset();
	}

	/** -Dcadence.fg=false: the hooks aren't applied (FrameGenMixinPlugin), so nothing is ever generated: never halve the cap. */
	private static final boolean HOOKS = !"false".equals(System.getProperty("cadence.fg"));
	private static long lastRunning;

	/** Neural's interpolator: 2 ready, 1 starting, 0 off, -1 can't run here (then Smooth's reprojection). */
	private static volatile int neuralState;

	public static int neuralState() {
		return neuralState;
	}

	/** Whether frames are being generated now. */
	public static boolean running() {
		return shown > 0;
	}

	public static int renderedRate() {
		return rendered;
	}

	/** The frames on screen a second, for the fps counter: the frames rendered plus those generated (once a second). */
	public static int shownFps(int renderedFps) {
		long now = System.nanoTime();
		if (now - reportNanos > 1_000_000_000L) {
			genRate = reportNanos == 0 ? 0 : (int) Math.round((genCount - genReported) * 1e9 / (now - reportNanos));
			genReported = genCount;
			reportNanos = now;
			if (LOG && genRate > 0) System.out.println("cadence-fg: " + genRate + " generated/s, gpu " + String.format(java.util.Locale.ROOT, "%.0f",
				fg != 0 ? FrameGenNative.gpuMicros(fg) : -1) + " us (responsive frames)");
		}
		return running() || genRate > 0 ? renderedFps + genRate : renderedFps;
	}

	/** Snapshots taken so far (self-tests). */
	public static long snapshots() {
		return frames;
	}

	public static int generatedPerSecond() {
		return genRate;
	}

	// ---- per level frame (render thread) ----

	/** LevelRenderer.render: the frame's camera (camera-relative rendering: its position, its rotation). */
	public static void camera(double x, double y, double z, Matrix4fc rotation) {
		if (!running()) return;
		camX = x;
		camY = y;
		camZ = z;
		viewRot.set(rotation);
		var p = Minecraft.getInstance().player;
		yawAt = p != null ? p.getYRot() : 0;
		pitchAt = p != null ? p.getXRot() : 0;
		cameraFresh = true;
	}

	/** GameRenderer.renderLevel: the projection the world is drawn with (view bobbing and nausea included). */
	public static void projection(Matrix4fc projection) {
		if (running()) proj.set(projection);
	}

	/**
	 * The world as drawn this frame, before the hand and the GUI: its colour and depth (any size: sampled to the presented
	 * size). packDepth: the depth is a shader pack's (1 - the game's: measured with fgcheck, the game's own taken as is rebuilt
	 * 72% of pixels exactly moving 0.3 blocks a frame, 11% read as OpenGL's -1..1). A shader pack's frame (its hand inside it):
	 * handDistance (blocks) keeps nearer pixels where they are on screen; 0 for the game's own frame (its hand comes after this).
	 */
	public static void snapshot(GpuTexture color, GpuTexture depth, boolean packDepth, float handDistance) {
		if (!running() || !cameraFresh || color == null || depth == null) return;
		cameraFresh = false;
		if (!init()) return;
		Minecraft mc = Minecraft.getInstance();
		var target = mc.gameRenderer.mainRenderTarget();
		int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
		if (target != null && target.width > 0) {
			w = target.width;
			h = target.height;
		}
		if (w != width || h != height) {
			FrameGenNative.size(fg, ctx, w, h);
			width = w;
			height = h;
			slotFrame[0] = slotFrame[1] = -10;
		}
		Matrix4f inv = new Matrix4f(proj).invert();
		inv.get(MemoryUtil.memByteBuffer(snapBuf, 64));
		MemoryUtil.memPutFloat(snapBuf + 64, packDepth ? 1 : 0);
		MemoryUtil.memPutFloat(snapBuf + 68, 0);
		MemoryUtil.memPutFloat(snapBuf + 72, 0);
		MemoryUtil.memPutFloat(snapBuf + 76, 0);
		int s = FrameGenNative.snapshot(fg, enc, MetalBridge.textureHandle(color), MetalBridge.textureHandle(depth), snapBuf, SNAP_BYTES);
		if (s < 0) return;
		slot = s;
		slotProj[s].set(proj);
		slotRot[s].set(viewRot);
		slotPos[s][0] = camX;
		slotPos[s][1] = camY;
		slotPos[s][2] = camZ;
		slotLook[s][0] = yawAt;
		slotLook[s][1] = pitchAt;
		slotFrame[s] = ++frames;
		slotTime[s] = System.nanoTime();
		hand = handDistance;
		// (the frame's format changed, HDR on or off: mcfg.m made both snapshot textures again, so the other slot holds nothing,
		// BH7-14)
		boolean f = MetalBridge.isFloatFrame(color);
		if (f != floatSnap) {
			floatSnap = f;
			slotFrame[s ^ 1] = -10;
		}
	}

	private static float hand;

	/**
	 * MetalSurface.blitFromTexture, before the real frame's present: Smooth's generated frame (the camera halfway between the
	 * last two snapshots) presented first. latestTexture: the frame about to be presented (GUI included).
	 */
	public static void beforePresent(long latestTexture) {
		latest = latestTexture;
		boolean fresh = frames != presented;  // (a snapshot since the last present)
		if (running() && !fresh) {
			if (++presentsWithout > 30) {
				idleUntil = System.nanoTime() + 3_000_000_000L;
				presentsWithout = 0;
				rendered = shown = 0;
			}
		} else {
			presentsWithout = 0;
		}
		if (!running() || fg == 0 || slot < 0 || !fresh || slotFrame[slot] != frames) return;
		presented = frames;
		int a = slot, b = slot ^ 1;
		neuralState = FrameGenNative.neural(fg, ctx, effective() == Mode.NEURAL);
		if (effective() == Mode.RESPONSIVE) {
			responsiveFor = frames;  // the limiter's wait makes this frame's in-between one
			return;
		}
		if (!consecutive(a, b)) return;
		try {
			smooth(a, b, latestTexture);
		} catch (RuntimeException | LinkageError e) {
			System.out.println("cadence-fg: smooth failed: " + e);
			e.printStackTrace(System.out);
		}
	}

	private static void smooth(int a, int b, long latestTexture) {
		// the in-between camera: halfway in position, rotation halfway on the sphere, the newest frame's projection
		double mx = (slotPos[a][0] + slotPos[b][0]) / 2, my = (slotPos[a][1] + slotPos[b][1]) / 2, mz = (slotPos[a][2] + slotPos[b][2]) / 2;
		Quaternionf qa = slotRot[a].getNormalizedRotation(new Quaternionf()), qb = slotRot[b].getNormalizedRotation(new Quaternionf());
		Matrix4f rot = new Matrix4f().rotation(qb.slerp(qa, 0.5f));
		writeFrame(a, b, true, mx, my, mz, slotProj[a], rot, false);
		long drawable = MetalBridge.nextDrawable();
		if (drawable == 0) {
			if (LOG) System.out.println("cadence-fg: no drawable");
			return;
		}
		FrameGenNative.presentInFrame(fg, enc, drawable, latestTexture, frameBuf, FRAME_BYTES);
		if (genCount++ == 0) System.out.println("[cadence] frame generation: first frame generated (" + effective().label + ")");
	}

	/**
	 * The frame limiter's wait (FramerateLimiterMixin): for Responsive, the time to make a frame in it, as nanoseconds from now
	 * (the middle of the wait), or -1 for none.
	 */
	public static long responsiveAt(long remainingNanos) {
		if (effective() != Mode.RESPONSIVE || !running() || responsiveFor != frames || remainingNanos < 2_000_000L) return -1;
		return remainingNanos / 2;
	}

	/** Responsive's frame: input read now, the newest snapshot seen from where the mouse has turned the camera since. */
	public static void responsiveFrame() {
		responsiveFor = -1;
		try {
			responsive();
		} catch (RuntimeException | LinkageError e) {
			System.out.println("cadence-fg: responsive frame failed: " + e);
		}
	}

	private static void responsive() {
		if (fg == 0 || slot < 0 || latest == 0) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		FrameGenInput.poll(mc);
		// (the poll runs the game's input handlers: a click may have left the world (WorldClosed reset the snapshots), opened a
		// screen or changed the window: then no frame now, BUG-HUNT BH4-01)
		var player = mc.player;
		if (player == null || mc.level == null || fg == 0 || slot < 0 || latest == 0 || mc.gui.screen() != null) return;
		int a = slot, b = slot ^ 1;
		boolean haveB = consecutive(a, b);
		// the turn since the snapshot, applied to its rotation (as Camera turns: yaw about world up, pitch about the camera's x)
		float dYaw = player.getYRot() - slotLook[a][0], dPitch = player.getXRot() - slotLook[a][1];
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		Matrix4f rot = new Matrix4f().rotationX((float) Math.toRadians(dPitch)).mul(slotRot[a]).rotateY((float) Math.toRadians(dYaw));
		// the camera's position carried on half a frame (as it moved between the last two)
		double mx = slotPos[a][0], my = slotPos[a][1], mz = slotPos[a][2];
		if (haveB) {
			mx += (slotPos[a][0] - slotPos[b][0]) / 2;
			my += (slotPos[a][1] - slotPos[b][1]) / 2;
			mz += (slotPos[a][2] - slotPos[b][2]) / 2;
		}
		writeFrame(a, b, haveB, mx, my, mz, slotProj[a], firstPerson ? rot : slotRot[a], true);
		long drawable = MetalBridge.nextDrawable();
		if (drawable == 0) return;
		FrameGenNative.presentAlone(fg, ctx, drawable, latest, frameBuf, FRAME_BYTES);
		if (genCount++ == 0) System.out.println("[cadence] frame generation: first frame generated (" + effective().label + ")");
	}

	/**
	 * Whether snapshot b is the frame right before a: the one before in count, taken just before (under 100 ms), the camera not
	 * moved more than 16 blocks or turned more than 60 degrees (a teleport, a respawn): else b is some older picture
	 * (FG paused for Photo Mode, a toggle, a menu, a dimension change) and reprojecting it would show a garbage frame.
	 */
	private static boolean consecutive(int a, int b) {
		if (slotFrame[b] != slotFrame[a] - 1 || slotTime[a] - slotTime[b] > 100_000_000L) return false;
		double dx = slotPos[a][0] - slotPos[b][0], dy = slotPos[a][1] - slotPos[b][1], dz = slotPos[a][2] - slotPos[b][2];
		if (dx * dx + dy * dy + dz * dz > 256) return false;
		return Math.abs(slotLook[a][0] - slotLook[b][0]) < 60 && Math.abs(slotLook[a][1] - slotLook[b][1]) < 60;
	}

	private static void writeFrame(int a, int b, boolean haveB, double mx, double my, double mz, Matrix4fc mProj, Matrix4fc mRot, boolean responsive) {
		long f = frameBuf;
		writeCam(f, slotProj[a], slotRot[a], 0, 0, 0);
		writeCam(f + 144, slotProj[b], slotRot[b], slotPos[b][0] - slotPos[a][0], slotPos[b][1] - slotPos[a][1], slotPos[b][2] - slotPos[a][2]);
		writeCam(f + 288, mProj, mRot, mx - slotPos[a][0], my - slotPos[a][1], mz - slotPos[a][2]);
		MemoryUtil.memPutFloat(f + 432, responsive ? 1 : 0);
		MemoryUtil.memPutFloat(f + 436, haveB ? 1 : 0);
		MemoryUtil.memPutFloat(f + 440, hand);
		MemoryUtil.memPutFloat(f + 444, 0);
		MemoryUtil.memPutFloat(f + 448, width);
		MemoryUtil.memPutFloat(f + 452, height);
		MemoryUtil.memPutFloat(f + 456, 1f / Math.max(1, width));
		MemoryUtil.memPutFloat(f + 460, 1f / Math.max(1, height));
	}

	private static void writeCam(long at, Matrix4fc p, Matrix4fc rot, double x, double y, double z) {
		viewProj.set(p).mul(rot);
		viewProj.get(MemoryUtil.memByteBuffer(at, 64));
		new Matrix4f(viewProj).invert().get(MemoryUtil.memByteBuffer(at + 64, 64));
		MemoryUtil.memPutFloat(at + 128, (float) x);
		MemoryUtil.memPutFloat(at + 132, (float) y);
		MemoryUtil.memPutFloat(at + 136, (float) z);
		MemoryUtil.memPutFloat(at + 140, 0);
	}

	/** Self-tests: the rate rendered while Frame Generation runs, whatever the preset says (0: as the preset says). */
	public static volatile int forceBase;

	/**
	 * Self-test (fgcheck): how well reprojection rebuilds a real frame. The older of the last two snapshots is reprojected to the
	 * newer one's camera (as Responsive does) and compared with the newer, pixel by pixel; and, for scale, the older unmoved.
	 * Returns a line, or null when the last two snapshots aren't consecutive frames.
	 */
	public static String check() {
		if (fg == 0 || slot < 0) return "no snapshots (running " + running() + ", generator " + (fg != 0) + ")";
		int a = slot, src = slot ^ 1;
		if (slotFrame[src] != slotFrame[a] - 1) return "snapshots not consecutive (" + slotFrame[src] + ", " + slotFrame[a] + ")";
		long bytes = (long) width * height * 4;
		long out = MemoryUtil.nmemAlloc(bytes * 2);
		try {
			double[] warp = checkOne(src, a, out, bytes), still = checkOne(src, src, out, bytes);
			if (warp == null || still == null) return "readback failed";
			double turn = Math.toDegrees(new Quaternionf().setFromNormalized(slotRot[a]).difference(new Quaternionf().setFromNormalized(slotRot[src]), new Quaternionf()).angle());
			return String.format(java.util.Locale.ROOT, "reprojected: %.1f%% of pixels within 6/255 (mean error %.2f); unmoved: %.1f%% (%.2f); camera turned %.2f deg, moved %.3f blocks",
				warp[0], warp[1], still[0], still[1], turn, Math.sqrt(sq(slotPos[a][0] - slotPos[src][0]) + sq(slotPos[a][1] - slotPos[src][1]) + sq(slotPos[a][2] - slotPos[src][2])));
		} finally {
			MemoryUtil.nmemFree(out);
		}
	}

	/** -Dcadence.fg.dump=PATH: fgcheck also writes PATH-warp-gen.png, -truth.png (and -still-...) to look at. */
	private static final String DUMP = System.getProperty("cadence.fg.dump");

	private static void dump(long out, long bytes, String path) {
		try {
			for (int k = 0; k < 2; k++) {
				var img = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
				for (int y = 0; y < height; y++)
					for (int x = 0; x < width; x++) {
						long i = k * bytes + ((long) (k == 0 ? y : height - 1 - y) * width + x) * 4;
						img.setRGB(x, y, (MemoryUtil.memGetByte(out + i) & 255) << 16 | (MemoryUtil.memGetByte(out + i + 1) & 255) << 8 | MemoryUtil.memGetByte(out + i + 2) & 255);
					}
				javax.imageio.ImageIO.write(img, "png", new java.io.File(path + (k == 0 ? "-gen.png" : "-truth.png")));
			}
		} catch (java.io.IOException e) {
			System.out.println("cadence-fg: dump failed: " + e);
		}
	}

	private static double sq(double v) {
		return v * v;
	}

	/** src reprojected to slot to's camera, against slot to's snapshot: {% of pixels within 6/255, mean max-channel error}. */
	private static double[] checkOne(int src, int to, long out, long bytes) {
		long f = frameBuf;
		writeCam(f, slotProj[src], slotRot[src], 0, 0, 0);
		writeCam(f + 144, slotProj[src], slotRot[src], 0, 0, 0);
		writeCam(f + 288, slotProj[to], slotRot[to], slotPos[to][0] - slotPos[src][0], slotPos[to][1] - slotPos[src][1], slotPos[to][2] - slotPos[src][2]);
		MemoryUtil.memPutFloat(f + 432, 1);
		MemoryUtil.memPutFloat(f + 436, 0);
		MemoryUtil.memPutFloat(f + 440, hand);
		MemoryUtil.memPutFloat(f + 444, 0);
		if (FrameGenNative.debug(fg, ctx, src, f, FRAME_BYTES, out) != 0) return null;
		if (DUMP != null) dump(out, bytes, DUMP + (src == to ? "-still" : "-warp"));
		long within = 0;
		double sum = 0;
		// (the generated frame is a render target's, top row first; the snapshot the game's, bottom row first)
		for (int y = 0; y < height; y++) {
			long gr = out + (long) y * width * 4, tr = out + bytes + (long) (height - 1 - y) * width * 4;
			for (int x = 0; x < width * 4; x += 4) {
				int e = 0;
				for (int c = 0; c < 3; c++) e = Math.max(e, Math.abs((MemoryUtil.memGetByte(gr + x + c) & 255) - (MemoryUtil.memGetByte(tr + x + c) & 255)));
				if (e <= 6) within++;
				sum += e;
			}
		}
		long n = bytes / 4;
		return new double[] {within * 100.0 / n, sum / n};
	}

	/** The world closed: the snapshots go (their memory too). */
	public static void reset() {
		slot = -1;
		slotFrame[0] = slotFrame[1] = -10;
		responsiveFor = -1;
		latest = 0;
		if (fg != 0 && width != 0) {
			FrameGenNative.size(fg, ctx, 0, 0);
			width = height = 0;
		}
	}

	private static boolean init() {
		if (fg != 0) return true;
		if (failed) return false;
		try {
			encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) throw new IllegalStateException("not on the Metal backend");
			ctx = MetalBridge.ctx(encoder);
			enc = MetalBridge.enc(encoder);
			String src;
			try (InputStream in = FrameGen.class.getResourceAsStream("/cadence/framegen/framegen.metal")) {
				src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			long s = MemoryUtil.nmemAlloc(src.length() * 3L + 1), err = MemoryUtil.nmemAlloc(2048);
			try {
				MemoryUtil.memUTF8(src, true, MemoryUtil.memByteBuffer(s, src.length() * 3 + 1));
				fg = FrameGenNative.create(ctx, s, err, 2048);
				if (fg == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			} finally {
				MemoryUtil.nmemFree(s);
				MemoryUtil.nmemFree(err);
			}
			frameBuf = MemoryUtil.nmemCalloc(1, FRAME_BYTES);
			snapBuf = MemoryUtil.nmemCalloc(1, SNAP_BYTES);
			System.out.println("[cadence] frame generation ready");
			return true;
		} catch (Exception | LinkageError e) {
			failed = true;
			rendered = shown = 0;
			System.out.println("[cadence] frame generation unavailable: " + e);
			return false;
		}
	}
}
