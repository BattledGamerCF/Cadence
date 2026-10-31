package dev.cadence.shaders.cadence.system;

import dev.cadence.metal.backend.MacSystem;
import dev.cadence.shaders.cadence.render.RenderScale;
import dev.cadence.shaders.cadence.ui.ShaderToggle;
import dev.cadence.shaders.cadence.shadow.ShadowReuse;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

/**
 * Cadence: Heat Control (Video Settings, on by default in the Balanced preset). A fanless Mac slows itself down as it heats
 * up; easing off a little before that keeps the game steady instead of fast-then-sluggish. macOS reports a thermal state
 * (nominal, fair, serious, critical) and Low Power Mode; SystemMonitor polls them every 2 seconds.
 *
 * <p>A thermostat, not a switch. The first version capped at the screen's refresh rate the moment the Mac was "warm" and gave
 * everything back a minute after it was "cool" again, so the Mac heated, eased off, cooled, released and heated again every
 * few minutes (and said so each time). Now:
 * <ul>
 * <li>Warm (or Low Power Mode): the frame rate is held at the screen's refresh rate (or the current frame rate, if lower).
 * macOS can report "warm" for a long time without slowing anything down, so warm alone holds there.</li>
 * <li>Cool again: nothing changes for 3 minutes, then the cap rises 5% a minute. If the Mac warms up again on the way, the
 * cap it had reached is remembered and later rises stop just below it (that memory loosens by 5% every 10 cool minutes), so
 * the cap settles where the Mac stays cool instead of oscillating. Once it reaches the player's own limit it is released.</li>
 * <li>Hot (macOS is throttling): also shadows redrawn at most every 2nd frame (3rd when critical) and, with a shader pack,
 * Auto resolution kept at 85% (70%) or below, and the cap is lowered 5% every 10 seconds while it stays hot, down to 30 fps. These come back 5 minutes after it's no longer hot.</li>
 * </ul>
 * Fixed settings the player chose are never lowered except the frame rate. A notice is shown when holding starts, when it
 * ends, and when it gets hot (at most one ordinary notice every 3 minutes). Nothing is saved: turning it off gives everything
 * back. -Dcadence.heatFast=true runs the clock 10x faster (self-tests).
 */
public final class HeatControl {
	/**
	 * Cadence: the cooling preference (Video Settings "Cooling"). How early Cadence eases off as the Mac heats up:
	 * <ul>
	 * <li>Quiet: when the Mac is merely warm, the frame rate is held at the screen's refresh rate (coolest, quietest).</li>
	 * <li>Balanced (default): warm holds at twice the screen's rate; hot (macOS slowing the Mac) eases off as Quiet.</li>
	 * <li>Max Performance: nothing until macOS actually throttles; then the frame rate is held, never below the screen's rate,
	 * and shadows/resolution ease only when it is critical. On a Mac with fans this lets macOS spin them up instead.</li>
	 * <li>Off: never eases off (macOS may still slow a hot Mac down).</li>
	 * </ul>
	 * Cadence never sets fan speed itself: macOS allows that only with administrator rights.
	 */
	public enum Cooling {
		QUIET("Quiet"), BALANCED("Balanced"), PERFORMANCE("Max Performance"), OFF("Off");

		public final String label;

		Cooling(String label) {
			this.label = label;
		}
	}

	private static volatile Cooling cooling = initialCooling();
	private static volatile boolean enabled = cooling != Cooling.OFF;

	/** cadence.heatControl=false (tests, old configs) is Off; otherwise the saved "cooling" value, Balanced by default. */
	private static Cooling initialCooling() {
		if ("false".equalsIgnoreCase(System.getProperty("cadence.heatControl", ShaderToggle.configValue("heatControl", "true")).strip()))
			return Cooling.OFF;
		String saved = System.getProperty("cadence.cooling", ShaderToggle.configValue("cooling", "balanced")).strip().toUpperCase(java.util.Locale.ROOT);
		try {
			return Cooling.valueOf(saved);
		} catch (IllegalArgumentException e) {
			return Cooling.BALANCED;
		}
	}

	public static Cooling cooling() {
		return cooling;
	}

	public static void setCooling(Cooling mode) {
		cooling = mode;
		enabled = mode != Cooling.OFF;
		ShaderToggle.saveKey("cooling", mode.name().toLowerCase(java.util.Locale.ROOT));
		ShaderToggle.saveKey("heatControl", Boolean.toString(enabled));
		release(false);
		// a new mode starts fresh: what the old one learned (and the frame rate its cap produced) says nothing about this one
		learned = releasedCap = Double.POSITIVE_INFINITY;
		releasedAt = 0;
		freshMode = true;
	}

	/**
	 * Cadence (ShaderSwitcher): a shader pack turned on, off or changed. The frame rate held so far was the old workload's
	 * (a Mac that warmed up under a pack running at 60 fps held 60 fps after the pack was turned off, for as long as it stayed
	 * warm), so the hold starts again at the cooling mode's ceiling; hot-only measures (shadows, resolution) stay as they are.
	 */
	public static void workloadChanged() {
		if (!enabled || !holding()) return;
		cap = Double.POSITIVE_INFINITY;
		warmSince = coolSince = 0;
		learned = releasedCap = Double.POSITIVE_INFINITY;
		releasedAt = 0;
		freshMode = true;
		LoggerFactory.getLogger("cadence").info("Heat control: shaders changed, the frame-rate hold starts again");
	}

	/** Just switched modes: the next hold starts at the mode's own ceiling, not at the frame rate the old cap allowed. */
	private static volatile boolean freshMode;
	private static final long SECOND = Boolean.getBoolean("cadence.heatFast") ? 100_000_000L : 1_000_000_000L;
	private static final long LOWER_EVERY = 10 * SECOND, RAISE_AFTER = 180 * SECOND, RAISE_EVERY = 60 * SECOND,
		LOOSEN_EVERY = 600 * SECOND, HOT_RELEASE = 300 * SECOND, NOTICE_GAP = 180 * SECOND;
	private static volatile int thermal, extras;
	private static volatile boolean lowPower;
	private static volatile int displayHz = 60;
	private static boolean displayPolled;
	/** The frame-rate cap in force (Double.POSITIVE_INFINITY: none), and the cap at which the Mac last warmed up again. */
	private static volatile double cap = Double.POSITIVE_INFINITY, learned = Double.POSITIVE_INFINITY;
	private static long warmSince, coolSince, lastLower, lastRaise, lastLoosen, notHotSince, lastNotice, releasedAt;
	/** The cap just before the last release: warming up again soon after means it was too high to keep. */
	private static double releasedCap = Double.POSITIVE_INFINITY;
	private static final long RECENT_RELEASE = 1200 * SECOND;
	/** Self-test: a pretend thermal state (-1 = the real one). */
	public static volatile int fakeThermal = -1;

	private HeatControl() {
	}

	public static boolean enabled() {
		return enabled;
	}

	/** Presets: on keeps the chosen cooling (Balanced if it was Off), off is Off. */
	public static void setEnabled(boolean on) {
		if (on == enabled) return;
		setCooling(on ? (cooling == Cooling.OFF ? Cooling.BALANCED : cooling) : Cooling.OFF);
	}

	/** 0 nothing eased, 1 frame rate held, 2 hot (shadows/resolution eased too), 3 critical. */
	public static int level() {
		if (!enabled) return 0;
		if (extras > 0) return extras;
		return holding() ? 1 : 0;
	}

	/** The refresh rate of the window's display (polled every 2 s), at least 30. */
	public static int refreshHz() {
		return displayHz;
	}

	private static boolean holding() {
		return cap != Double.POSITIVE_INFINITY;
	}

	/** SystemMonitor, every 2 s. */
	static void poll(long now) {
		thermal = fakeThermal >= 0 ? fakeThermal : MacSystem.thermalState();
		lowPower = MacSystem.lowPowerMode();
		int hz = Math.max(30, MacSystem.displayHz());
		// (the window moved to another display, or its refresh rate changed: the caps that follow it follow too)
		if (hz != displayHz && displayPolled)
			org.slf4j.LoggerFactory.getLogger("cadence").info("Display: {} Hz (was {} Hz)", hz, displayHz);
		displayHz = hz;
		displayPolled = true;
		if (!enabled) return;
		Cooling mode = cooling;
		boolean performance = mode == Cooling.PERFORMANCE;
		// when holding starts, the highest it holds at, and when shadows/resolution ease too
		boolean warm = thermal >= (performance ? 2 : 1) || lowPower;
		// Max Performance holds at whatever the Mac manages when macOS starts throttling; the others at most 1x / 2x the screen
		double ceiling = mode == Cooling.QUIET ? displayHz : performance ? Double.MAX_VALUE : Math.max(displayHz * 2.0, 120);
		int hot = Math.min(thermal, 3) >= (performance ? 3 : 2) ? Math.min(thermal, 3) : 0;
		double floor = hot > 0 ? (performance ? displayHz : 30) : Math.max(30, displayHz * 0.75);
		if (performance && thermal >= 2 && hot == 0) floor = Math.max(floor, displayHz);
		if (warm) {
			coolSince = 0;
			if (!holding()) {
				if (releasedAt != 0 && now - releasedAt < RECENT_RELEASE) {
					// warm again soon after letting go: the full rate is more than this Mac keeps up; stay below it from now on
					learned = Math.min(learned, releasedCap);
					log("warm again after a release", now);
				}
				double fps = freshMode ? 0 : Minecraft.getInstance().getFps();
				// (a rate under the screen's refresh is a moment, not what the Mac can do: a world loading, chunks arriving, a pack
				// compiling. Starting the hold there kept whole sessions at 45, shown as 30 on a 60 Hz screen: start at the ceiling)
				if (fps < displayHz) fps = 0;
				cap = Math.max(floor, Math.min(ceiling, fps > 0 ? fps : Math.min(ceiling, limit())));
				if (learned != Double.POSITIVE_INFINITY) cap = Math.min(cap, learned);
				freshMode = false;
				warmSince = now;
				lastLower = now;
				notice("Your Mac is warm: Cadence is holding about " + Math.round(cap) + " fps to keep it steady", describe(), false, now);
			} else if (warmSince == 0) {
				// warmed up again while the cap was being raised: remember where, and go back to the screen's rate
				warmSince = now;
				lastLower = now;
				learned = Math.min(learned, cap);
				cap = Math.max(floor, Math.min(cap * 0.95, Math.min(ceiling, limit())));
				log("warm again on the way back up", now);
			} else if ((hot > 0 || performance && thermal >= 2) && now - lastLower >= LOWER_EVERY && cap > floor) {
				// only while macOS is actually slowing the Mac down; merely warm ("fair") holds the screen's rate
				lastLower = now;
				cap = Math.max(floor, cap * 0.95);
			}
			if (cap < floor) cap = floor;
		} else if (holding()) {
			warmSince = 0;
			if (coolSince == 0) {
				coolSince = now;
				lastRaise = now;
				lastLoosen = now;
			}
			if (learned != Double.POSITIVE_INFINITY && now - lastLoosen >= LOOSEN_EVERY) {
				lastLoosen = now;
				learned *= 1.05;
			}
			if (now - coolSince >= RAISE_AFTER && now - lastRaise >= RAISE_EVERY) {
				lastRaise = now;
				double next = cap * 1.05;
				if (learned != Double.POSITIVE_INFINITY) next = Math.min(next, learned * 0.97);
				if (next >= limit() * 0.98) release(true);
				else cap = Math.max(cap, next);
			}
		}
		// hot: shadows and resolution too, given back 5 minutes after it isn't hot any more
		if (hot > extras) {
			applyExtras(hot);
			notHotSince = 0;
			notice(hot >= 3 ? "Your Mac is very hot: Cadence eased off more" : "Your Mac is hot: Cadence eased off more", describe(), true, now);
		} else if (extras > 0 && hot < extras) {
			if (notHotSince == 0) notHotSince = now;
			else if (now - notHotSince >= HOT_RELEASE) {
				notHotSince = 0;
				applyExtras(hot);
			}
		} else notHotSince = 0;
	}

	/** The player's own frame-rate limit (Max Framerate; 260 is Unlimited). */
	private static double limit() {
		int l = Minecraft.getInstance().options.framerateLimit().get();
		return l >= 260 ? Math.max(displayHz * 4, 240) : l;
	}

	private static void release(boolean noticed) {
		boolean was = holding() || extras > 0;
		if (holding() && noticed) {
			releasedCap = cap;
			releasedAt = System.nanoTime();
		}
		cap = Double.POSITIVE_INFINITY;
		warmSince = coolSince = 0;
		applyExtras(0);
		if (was && noticed) notice("Your Mac cooled down: back to full speed", "", false, System.nanoTime());
	}

	private static void applyExtras(int hot) {
		extras = hot;
		ShadowReuse.setHeatMinimum(hot >= 3 ? 3 : hot >= 2 ? 2 : 1);
		RenderScale.setCeiling(hot >= 3 ? 0.70f : hot >= 2 ? 0.85f : 1.0f);
	}

	private static void notice(String title, String detail, boolean always, long now) {
		log(title, now);
		if (!always && lastNotice != 0 && now - lastNotice < NOTICE_GAP) return;
		lastNotice = now;
		Notices.post(title, detail);
	}

	private static void log(String what, long now) {
		LoggerFactory.getLogger("cadence").info("Heat control: {} (thermal state {}, low power {}, cap {}, learned {})",
			what, thermal, lowPower, holding() ? Math.round(cap) : "none",
			learned == Double.POSITIVE_INFINITY ? "none" : Math.round(learned));
	}

	/** The frame-rate limit heat control asks for, or Integer.MAX_VALUE for none. */
	public static int fpsCap() {
		if (!enabled || !holding()) return extras >= 3 && enabled ? Math.min(60, displayHz) : Integer.MAX_VALUE;
		int c = (int) Math.round(cap);
		return extras >= 3 ? Math.min(c, Math.min(60, displayHz)) : c;
	}

	public static int displayHz() {
		return displayHz;
	}

	/** What heat control is doing right now, in plain words ("" when nothing). */
	public static String describe() {
		int l = level();
		if (l == 0) return "";
		List<String> parts = new ArrayList<>();
		if (fpsCap() != Integer.MAX_VALUE) parts.add("about " + fpsCap() + " fps");
		if (l >= 2) parts.add("shadows every " + (l >= 3 ? "3rd" : "2nd") + " frame");
		if (l >= 2 && RenderScale.isAuto() && dev.cadence.shaders.runtime.ShaderRuntime.get() != null)
			parts.add("Auto resolution at most " + Math.round(RenderScale.ceiling() * 100) + "%");
		String why = thermal >= 2 ? "Mac is hot" : thermal == 1 ? "Mac is warm" : lowPower ? "Low Power Mode is on" : "Mac is cooling down";
		return why + ": " + String.join(", ", parts);
	}

	/** One short line for the settings screen. */
	public static String shortDescribe() {
		int l = level();
		if (l == 0) return "";
		String why = thermal >= 2 ? "Mac hot" : thermal == 1 ? "Mac warm" : lowPower ? "Low Power Mode" : "cooling down";
		String what = (fpsCap() != Integer.MAX_VALUE ? "holding ~" + fpsCap() + " fps" : "")
			+ (l >= 2 ? ", shadows every " + (l >= 3 ? "3rd" : "2nd") + " frame" : "");
		return "Heat (" + why + "): " + what;
	}

	/** The thermal state macOS last reported, in words (for the settings tooltip). */
	public static String thermalWords() {
		return switch (thermal) {
			case 0 -> "cool";
			case 1 -> "warm";
			case 2 -> "hot (macOS is slowing it down)";
			default -> "very hot";
		} + (lowPower ? ", Low Power Mode on" : "");
	}
}
