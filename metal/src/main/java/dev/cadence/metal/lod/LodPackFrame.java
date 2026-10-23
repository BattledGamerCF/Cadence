package dev.cadence.metal.lod;

/**
 * Cadence: the far terrain drawn by a shader pack (its dh_terrain program, as with the Distant Horizons mod under Iris). The
 * shader runtime says each frame whether its pack has such a program ({@link #want}); while it does, the far terrain culls as
 * usual at the start of the level's solid pass ({@link #prepare}) and draws nothing itself, and the pack's program draws the
 * culled quads from the far terrain's own buffers ({@link #records}, {@link #args}): no copy, no extra memory.
 *
 * <p>Kept free of the far terrain's other classes, so the shader runtime can ask it without loading them: with far terrain
 * off, {@link #ready} is always false.
 */
public final class LodPackFrame {
	private LodPackFrame() {
	}

	/** Whether the current pack draws the far terrain (set by the shader runtime before each frame). */
	static volatile boolean wanted;
	/** This frame's quads (MTLBuffer handles; columns.metal lod_mesh_pack's records) and draw arguments, or 0. */
	private static volatile long records, args;
	private static volatile boolean ready;

	/** The shader runtime: whether its pack has a dh_terrain program (false also when no pack is on). */
	public static void want(boolean on) {
		wanted = on;
	}

	/** Far terrain is on in this launch (its mixins are wired in). */
	public static boolean available() {
		return LodConfig.ENABLED;
	}

	/**
	 * Start of the level's solid pass (the sky drawn, the real terrain not yet): the far terrain's work for this frame, culled
	 * for the pack. Afterwards {@link #ready} says whether there is anything to draw.
	 */
	public static void prepare() {
		ready = false;
		if (wanted && LodConfig.ENABLED) Lod.drawForPack();
	}

	/** Lod: this frame's culled quads are in these buffers. */
	static void publish(long recordBuffer, long argsBuffer) {
		records = recordBuffer;
		args = argsBuffer;
		ready = recordBuffer != 0 && argsBuffer != 0;
	}

	/** Whether this frame has far terrain for the pack to draw (after {@link #prepare}). */
	public static boolean ready() {
		return ready;
	}

	/** The quads' records (uvec4 each; columns.metal lod_mesh_pack), a native MTLBuffer. */
	public static long records() {
		return records;
	}

	/**
	 * The draw's arguments (a native MTLBuffer): at offset 0 an indexed indirect draw of instances of 64 quads, the quad
	 * count in word 14, the camera's offset from the far terrain's origin (float bits) in words 116-118, the origin's y in word 119, the earth's curvature (float bits) in word 120.
	 */
	public static long args() {
		return args;
	}

	/** (Far Terrain page) Pre-generates within `chunks` chunks of the camera: tiles queued; 0 all cached; -1 not possible here. */
	public static int pregenerate(int chunks) {
		return LodConfig.ENABLED ? Lod.pregenerate(chunks) : -1;
	}

	/** A line for the HUD while the far terrain fills in or pre-generates, else null. */
	public static @org.jspecify.annotations.Nullable String status() {
		return LodConfig.ENABLED ? Lod.status() : null;
	}

	/** The player left the world (WorldClosed): the far terrain's memory goes now, its tiles and beacons are saved. */
	public static void worldClosed() {
		if (LodConfig.ENABLED) Lod.worldClosed();
	}

	/** The far terrain's reach in blocks while it draws, else 0. */
	public static float reach() {
		return LodConfig.ENABLED ? Lod.activeReach() : 0;
	}
}
