package dev.cadence.shaders.cadence.system;

import dev.cadence.metal.MacSystem;
import dev.cadence.shaders.cadence.ui.ShaderToggle;
import java.util.Locale;

/**
 * Cadence: "On Battery" (Video Settings). Unplugged, a Mac drawing 500 frames a second shows the display's 60 or 120 of them and
 * turns the rest of its battery into heat. While the Mac runs on its battery: Match Display (the default) caps the frame rate
 * at the display's refresh rate (60, or 120 on ProMotion; nothing visible is lost), Saver does what the Battery Saver preset
 * does (BatterySaver: a true 60 without a shader pack, 30 shown as 60 with one; the cap here is its 30), Off leaves it as when
 * plugged in. Plugged in again, the cap goes at once. Only the frame-rate limit changes (FramerateCapMixin), as with
 * In Background; the game itself runs as before. The power source is read from macOS at most every two seconds.
 * -Dcadence.batteryMode=off|display|saver, or the button.
 */
public final class BatteryMode {
	public enum Mode {
		OFF("Off"), DISPLAY("Match Display"), SAVER("Saver");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	public static final int SAVER_FPS = 30;
	private static final long CHECK_NS = 2_000_000_000L;
	private static volatile Mode mode = parse(System.getProperty("cadence.batteryMode", ShaderToggle.configValue("batteryMode", "display")));
	private static volatile boolean onBattery;
	private static long checkedAt;
	private static boolean checked;
	/** Self-tests: behave as if on battery. */
	public static volatile boolean fakeBattery;

	private BatteryMode() {
	}

	private static Mode parse(String s) {
		return switch (s.strip().toLowerCase(Locale.ROOT)) {
			case "off", "false" -> Mode.OFF;
			case "saver" -> Mode.SAVER;
			default -> Mode.DISPLAY;
		};
	}

	public static Mode mode() {
		return mode;
	}

	/** Video Settings: the next mode (Match Display, Saver, Off), saved. */
	public static void cycle() {
		mode = switch (mode) {
			case DISPLAY -> Mode.SAVER;
			case SAVER -> Mode.OFF;
			case OFF -> Mode.DISPLAY;
		};
		ShaderToggle.saveKey("batteryMode", mode.name().toLowerCase(Locale.ROOT));
	}

	/**
	 * Whether Saver is holding the frame rate now: the Mac on its battery with the mode Saver, or macOS's Low Power Mode on
	 * (the player asked the whole Mac to save power: Saver whatever the mode, on battery or not, unless the mode is Off).
	 */
	public static boolean saving() {
		return mode() == Mode.SAVER && onBattery() || mode() != Mode.OFF && lowPower();
	}

	private static boolean lowPower, lowPowerChecked;
	private static long lowPowerAt;

	/** macOS Low Power Mode (cached for two seconds). */
	public static boolean lowPower() {
		long now = System.nanoTime();
		if (!lowPowerChecked || now - lowPowerAt > CHECK_NS) {
			lowPowerChecked = true;
			lowPowerAt = now;
			lowPower = MacSystem.lowPowerMode();
		}
		return lowPower;
	}

	/** Whether the Mac is on its battery (cached for two seconds; render thread). */
	public static boolean onBattery() {
		if (fakeBattery) return true;
		long now = System.nanoTime();
		// (a flag for the first read: checkedAt = Long.MIN_VALUE overflowed "now - checkedAt" and macOS was never asked)
		if (!checked || now - checkedAt > CHECK_NS) {
			checked = true;
			checkedAt = now;
			onBattery = MacSystem.onBattery();
		}
		return onBattery;
	}

	/** The frame-rate limit this asks for, or Integer.MAX_VALUE for none. */
	public static int fpsCap() {
		Mode m = mode;
		if (m != Mode.OFF && lowPower()) return SAVER_FPS;
		if (m == Mode.OFF || !onBattery()) return Integer.MAX_VALUE;
		// (the display rate HeatControl polls every 2 s: this runs every frame, and asking macOS each time cost a native call)
		return m == Mode.SAVER ? SAVER_FPS : Math.max(30, HeatControl.displayHz());
	}
}
