package dev.cadence.shaders.cadence.system;

/**
 * Cadence: what Battery Saver does (the Performance preset, On Battery: Saver while unplugged, and macOS Low Power Mode),
 * chosen by measuring the whole Mac's draw (M2 MacBook Air, fullscreen Retina, render and simulation distance 16):
 * <ul>
 * <li>Without a shader pack (and without Ray Traced Lighting): a true 60 fps, every frame rendered. Rendering such a frame
 * costs no more than generating one: 30 rendered + 30 generated drew 5.1 W, 60 rendered 5.0-5.2 W, so the real frames win
 * (sharper, no reprojection edges, a frame less delay). 30 fps alone drew 3.9-4.4 W but isn't smooth.</li>
 * <li>With a shader pack (or Ray Traced Lighting), where a frame is expensive: 30 rendered, 60 shown, and the world drawn at
 * 50% at most (Auto resolution may go lower, never higher): BSL 21.5 W at 60 fps, 14.4-15.5 W at 30 + 30 with Auto's
 * ~65-70%, 9.4-10.6 W at 50%. Frame Generation runs Responsive (FrameGen.saverResponsive): Smooth's frames slipped to
 * 27-29 rendered at Battery Saver's low clocks, Responsive's held a steady 30 for ~1 W more (10.9-11.3 W).</li>
 * </ul>
 * FramerateCapMixin decides every two seconds whether it is on ({@link #active}); RenderScale holds the resolution.
 */
public final class BatterySaver {
	/** The world's resolution at most while saving with a shader pack. */
	public static final float PACK_SCALE = 0.5f;
	/** Whether Battery Saver is on now (the preset, or On Battery: Saver unplugged, or Low Power Mode). */
	public static volatile boolean active;

	private BatterySaver() {
	}

	/** The frame is expensive (a shader pack or Ray Traced Lighting): then 30 rendered and 60 shown, else a true 60. */
	public static boolean heavy() {
		return dev.cadence.shaders.runtime.ShaderRuntime.get() != null || dev.cadence.metal.rt.RayTracing.mode().lighting();
	}

	/** The highest world resolution while saving with a shader pack, else 1. */
	public static float scaleCeiling() {
		return active && dev.cadence.shaders.runtime.ShaderRuntime.get() != null ? PACK_SCALE : 1.0f;
	}
}
