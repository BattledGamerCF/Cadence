package dev.cadence.shaders.cadence.head;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Head Tracking (Video Settings > Cadence: Extras): the view follows your head, from the motion sensors in AirPods (and other
 * headphones Core Motion tracks the head with). Cadence Helper reads the head's turn (Core Motion's headphone motion, on this
 * Mac only) and writes it to a small file both map, about 60 times a second; each frame the newest pose moves the view.
 *
 * <p>Lean: the camera only, a little: tilt your head to lean round a corner (up to a third of a block, never into a block),
 * turn it to glance a few degrees. Your aim stays where the mouse puts it. Look: your head turns the view, amplified (turn
 * your head a little, the view turns more, so your eyes stay on the screen), and your aim turns with it, as the mouse does.
 * Where you look straight at the screen is found again slowly by itself (over about 20 seconds), and at once when tracking
 * starts. The first time, macOS asks to let Cadence Helper use Motion & Fitness. In first person only.
 */
public final class HeadTracking {
	public enum Mode {
		OFF("Off"), LEAN("Lean"), LOOK("Look");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	/** -Dcadence.head.fake=true: the helper makes up a slow head movement (tests, without headphones or the permission). */
	private static final boolean FAKE = Boolean.getBoolean("cadence.head.fake");
	private static final float LOOK_YAW = 2.2f, LOOK_PITCH = 1.6f, LEAN_BLOCKS = 0.32f, GLANCE = 0.3f;
	private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
	private static volatile Mode mode = Mode.OFF;
	private static volatile String status = "";
	private static Path out;
	private static MappedByteBuffer map;
	private static boolean running, hooked, based;
	private static int session, lastSeq = -1;
	private static float rawYaw, rawPitch, rawRoll, baseYaw, basePitch, baseRoll;
	/** The head's turn from looking at the screen (degrees, smoothed), and what Look has added to the player's turn. */
	private static float yaw, pitch, roll, appliedYaw, appliedPitch;
	private static long lastFrame, lastPose;

	private HeadTracking() {
	}

	public static Mode mode() {
		return mode;
	}

	public static String status() {
		return status;
	}

	public static void setMode(Mode m) {
		mode = m == null ? Mode.OFF : m;
		if (mode == Mode.OFF) stop();
		based = false;  // (a new mode: straight ahead found again)
	}

	/** The head's turn now, for the self-test: yaw, pitch, roll in degrees from straight ahead. */
	public static String last() {
		return String.format(java.util.Locale.ROOT, "%s %s yaw %.1f pitch %.1f roll %.1f (look added %.1f/%.1f)", mode.label, status, yaw, pitch, roll,
			appliedYaw, appliedPitch);
	}

	private static Path dir() {
		return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Cadence");
	}

	private static void start() {
		Path app = dev.cadence.metal.neural.Helper.app();
		if (app == null) {
			status = "Can't: no Cadence Helper";
			mode = Mode.OFF;
			return;
		}
		try {
			long pid = ProcessHandle.current().pid();
			Files.createDirectories(dir());
			out = dir().resolve("head-" + pid + "-" + ++session + ".bin");
			Files.write(out, new byte[32]);
			try (FileChannel ch = FileChannel.open(out, StandardOpenOption.READ)) {
				map = ch.map(FileChannel.MapMode.READ_ONLY, 0, 32);
				map.order(ByteOrder.LITTLE_ENDIAN);
			}
			lastSeq = -1;
			if (!hooked) {
				hooked = true;
				Runtime.getRuntime().addShutdownHook(new Thread(HeadTracking::stop, "cadence-head-stop"));
			}
			// (open: LaunchServices starts it as its own app, so Motion & Fitness is asked for Cadence Helper)
			var cmd = new java.util.ArrayList<>(java.util.List.of("/usr/bin/open", "-n", "-g", "-a", app.toString(), "--args", "head", out.toString(),
				"--parent", String.valueOf(pid)));
			if (FAKE) cmd.add("--fake");
			new ProcessBuilder(cmd).start().waitFor();
			running = true;
			status = "Starting";
		} catch (Exception e) {
			status = "Can't: " + e.getMessage();
			mode = Mode.OFF;
		}
	}

	private static synchronized void stop() {
		if (!running) return;
		running = false;
		status = "";
		try {
			if (out != null) Files.writeString(out.resolveSibling(out.getFileName() + ".stop"), "");  // (the helper deletes both)
		} catch (Exception ignored) {
		}
		out = null;
		map = null;
	}

	/** Each client tick (ShaderKeys.tick): the helper started or stopped as the setting says. */
	public static void tick(Minecraft mc) {
		if (mode == Mode.OFF) {
			release();
			return;
		}
		if (!running) start();
	}

	/** Look's turn given back (Off, or the helper stopped): the view goes back to where the mouse put it. */
	private static void release() {
		var player = Minecraft.getInstance().player;
		if (player != null && (appliedYaw != 0 || appliedPitch != 0)) player.turn(-appliedYaw / 0.15, -appliedPitch / 0.15);
		appliedYaw = appliedPitch = 0;
		yaw = pitch = roll = 0;
	}

	/** The newest pose from the helper's file (a sequence lock: odd while it writes); false if nothing new. */
	private static boolean poll() {
		MappedByteBuffer m = map;
		if (m == null) return false;
		int s1 = (int) INT.getAcquire(m, 0);
		if ((s1 & 1) != 0 || s1 == lastSeq) return false;
		int state = m.getInt(4);
		float y = m.getFloat(8), p = m.getFloat(12), r = m.getFloat(16);
		VarHandle.acquireFence();
		if ((int) INT.getAcquire(m, 0) != s1) return false;
		lastSeq = s1;
		status = switch (state) {
			case 0 -> "Starting";
			case 1 -> "";
			case 2 -> "Connect AirPods";
			case 3 -> "Not allowed: Privacy > Motion";
			default -> "Not on this Mac";
		};
		if (state != 1) return false;
		rawYaw = (float) Math.toDegrees(y);
		rawPitch = (float) Math.toDegrees(p);
		rawRoll = (float) Math.toDegrees(r);
		return true;
	}

	/** Render thread, before the camera follows the player (CameraHeadMixin): the pose; Look turns the player by its change. */
	public static void beforeCamera(Minecraft mc) {
		Mode md = mode;
		if (md == Mode.OFF || !running) return;
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (poll()) {
			lastPose = now;
			if (!based) {
				baseYaw = rawYaw;
				basePitch = rawPitch;
				baseRoll = rawRoll;
				based = true;
			}
		}
		boolean fresh = based && now - lastPose < 500_000_000L;
		// straight ahead found again slowly (the head's drift, and a new way of sitting)
		float drift = Math.min(1f, dt / 20f);
		baseYaw += wrap(rawYaw - baseYaw) * drift;
		basePitch += (rawPitch - basePitch) * drift;
		baseRoll += (rawRoll - baseRoll) * drift;
		float k = Math.min(1f, dt / 0.05f);
		yaw += ((fresh ? dead(wrap(rawYaw - baseYaw)) : 0) - yaw) * k;
		pitch += ((fresh ? dead(rawPitch - basePitch) : 0) - pitch) * k;
		roll += ((fresh ? dead(rawRoll - baseRoll) : 0) - roll) * k;
		var player = mc.player;
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		if (md == Mode.LOOK && player != null && firstPerson) {
			// (head turning left is Core Motion's +yaw; the game's yaw turns right as it grows; head up is +pitch, the game's is down)
			float wantYaw = -yaw * LOOK_YAW, wantPitch = Math.clamp(-pitch * LOOK_PITCH, -60f, 60f);
			float beforeX = player.getXRot();
			player.turn((wantYaw - appliedYaw) / 0.15, (wantPitch - appliedPitch) / 0.15);
			appliedYaw = wantYaw;
			appliedPitch += player.getXRot() - beforeX;  // (what the game's pitch clamp let through)
		} else if (appliedYaw != 0 || appliedPitch != 0) {
			release();
		}
	}

	/** Render thread, once the camera has followed the player (CameraHeadMixin): Lean's glance and lean, the camera only. */
	public static Vec3 lean(Camera cam, Minecraft mc, float[] rotation) {
		if (mode != Mode.LEAN || !running || cam.isDetached() || mc.level == null) return null;
		rotation[0] = cam.yRot() - yaw * GLANCE;
		rotation[1] = Math.clamp(cam.xRot() - pitch * GLANCE, -90f, 90f);
		float side = Math.clamp(roll / 25f, -1f, 1f) * LEAN_BLOCKS;
		var left = cam.leftVector();
		Vec3 to = cam.position().add(-left.x() * side, -left.y() * side, -left.z() * side);
		// (never into a block: the lean stops short)
		for (int i = 0; i < 4; i++) {
			BlockPos p = BlockPos.containing(to);
			if (mc.level.getBlockState(p).isAir() || !mc.level.getBlockState(p).isCollisionShapeFullBlock(mc.level, p)) return to;
			to = cam.position().add(to.subtract(cam.position()).scale(0.5));
		}
		return cam.position();
	}

	private static float dead(float deg) {
		float a = Math.abs(deg);
		return a < 1.5f ? 0 : Math.signum(deg) * (a - 1.5f);
	}

	private static float wrap(float deg) {
		deg %= 360f;
		if (deg > 180f) deg -= 360f;
		if (deg < -180f) deg += 360f;
		return deg;
	}
}
