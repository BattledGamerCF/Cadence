package dev.cadence.shaders.cadence.shadow;

import dev.cadence.shaders.cadence.ui.ShaderToggle;

import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.LoggerFactory;

/**
 * Cadence: the shadow map is drawn every Nth frame (config shadowRate=, default 2; 1 = every frame, as before) and reused in
 * between.
 *
 * <p>The map is a picture of the world from the sun; a frame that reuses it gets the shadow matrices it was drawn with,
 * shifted by exactly how far the camera has moved since (the pack's shadow matrices map camera-relative positions), so every
 * lookup lands on the same texel as in the frame it was drawn: still terrain's shadows are exactly where they were. What is
 * one frame old in a reused frame: shadows of things that move (mobs, the player, swaying leaves) and the sun's angle (a
 * fraction of a hundredth of a degree). The map is always redrawn when it can't be reused: the first frame, a new pack,
 * size or dimension (new targets), a jump of more than 4 blocks, or the sun moving more than 0.5 degrees (/time set).
 *
 * <p>-Dcadence.shadowRate=N overrides the config; self-test step shadowrate=N.
 */
public final class ShadowReuse {
	private static volatile int rate = parse(System.getProperty("cadence.shadowRate", ShaderToggle.configValue("shadowRate", "2")));
	private static final boolean LOG = Boolean.getBoolean("cadence.probe") || System.getProperty("cadence.selftest") != null;

	private static final Matrix4f projection = new Matrix4f(), view = new Matrix4f();
	private static final Vector3f sun = new Vector3f();
	private static Vec3 camera;
	private static Object targets;
	private static int sinceDrawn;
	private static boolean reusing;
	private static long drawn, reused, logged;

	private ShadowReuse() {
	}

	private static int parse(String value) {
		try {
			return Math.max(1, Math.min(4, Integer.parseInt(value.strip())));
		} catch (NumberFormatException e) {
			return 2;
		}
	}

	public static int rate() {
		return rate;
	}

	/** Cadence (HeatControl): a temporary minimum rate while the Mac is hot (not saved); 1 = none. */
	private static volatile int heatMin = 1;

	public static void setHeatMinimum(int n) {
		heatMin = Math.max(1, Math.min(4, n));
	}

	/** Cadence (PackCheck): every frame drawn while a pack check runs. */
	public static volatile boolean suspended;

	/** The rate in use: the setting, or the heat minimum if higher. */
	public static int effectiveRate() {
		if (suspended) return 1;
		return Math.max(rate, heatMin);
	}

	/** Not saved (self-test A/B). */
	public static void setRate(int n) {
		rate = Math.max(1, Math.min(4, n));
	}

	/**
	 * At extraction, before the frame decides: whether it will reuse the map (same counter as {@link #decide}; only a forced
	 * redraw — new targets, a jump, a sun change — makes it draw instead, and then entities seen only by the shadow pass
	 * miss that one map).
	 */
	public static boolean predictReuse() {
		int r = effectiveRate();
		return r > 1 && camera != null && sinceDrawn + 1 < r;
	}

	/** This frame reuses the last shadow map (decided in {@link #decide}, read by the shadow pass). */
	public static boolean reusing() {
		return reusing;
	}

	/**
	 * Start of a pack frame, before the targets are cleared: whether this frame reuses the map. {@code currentTargets}: the
	 * pack's render targets (a new object = a new, empty map).
	 */
	public static boolean decide(Object currentTargets, Vec3 cameraPos, Vector3fc sunDirection) {
		reusing = false;
		if (effectiveRate() > 1 && camera != null && currentTargets == targets && sinceDrawn + 1 < effectiveRate()
			&& cameraPos.distanceToSqr(camera) <= 16 && sun.angle(sunDirection) <= Math.toRadians(0.5)) {
			reusing = true;
			sinceDrawn++;
			reused++;
		}
		if (LOG && System.nanoTime() - logged > 30_000_000_000L) {
			logged = System.nanoTime();
			LoggerFactory.getLogger("cadence").info("Shadow reuse (rate {}): {} maps drawn, {} frames reused", rate, drawn, reused);
		}
		return reusing;
	}

	/** A frame that draws the map: remember what it was drawn with. */
	public static void drawn(Object currentTargets, Vec3 cameraPos, Vector3fc sunDirection, Matrix4fc shadowProjection,
		Matrix4fc shadowView) {
		targets = currentTargets;
		camera = cameraPos;
		sun.set(sunDirection);
		projection.set(shadowProjection);
		view.set(shadowView);
		sinceDrawn = 0;
		drawn++;
	}

	/** A reusing frame: the drawn frame's projection. */
	public static Matrix4fc projection() {
		return projection;
	}

	/** A reusing frame: the drawn frame's view, shifted by the camera's movement since (camera-relative positions). */
	public static Matrix4f view(Vec3 cameraPos) {
		return new Matrix4f(view).translate((float) (cameraPos.x - camera.x), (float) (cameraPos.y - camera.y),
			(float) (cameraPos.z - camera.z));
	}

	public static String stats() {
		return String.format(Locale.ROOT, "rate %d, %d drawn, %d reused", rate, drawn, reused);
	}
}
