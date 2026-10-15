// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

/** -Dcadence.metal.residentAnim: see MetalDevice. The flag and the marker its mixin sets while a sprite animation's frames are created. */
public final class ResidentAnim {
	public static final boolean ON = Boolean.getBoolean("cadence.metal.residentAnim");
	public static volatile boolean creating;

	private ResidentAnim() {
	}
}
