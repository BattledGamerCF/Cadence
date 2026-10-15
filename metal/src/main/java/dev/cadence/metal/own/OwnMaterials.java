// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Material classes per quad for the native shading pipeline's G-buffer (dev.cadence.metal.shade.ShadeOwn): while a section compiles,
 * the class of every block of it (shade's ShadeBlocks numbers, installed through PackBlocks) goes into a per-thread grid
 * (OwnIntCompilerMixin); the store pass (OwnTerrain.store, same worker, right after the compile) gives every quad the class of
 * the block it belongs to and a bit per corner in the upper half of the quad (for the waving plants), one byte a quad.
 * Only with -Dcadence.shade=native (and -Dcadence.own=true).
 */
public final class OwnMaterials {
	public static final boolean ON = false; // native shading is not part of this build
	private static final ThreadLocal<byte[]> GRID = ThreadLocal.withInitial(() -> new byte[4096]);
	private static final ThreadLocal<boolean[]> COMPILING = ThreadLocal.withInitial(() -> new boolean[1]);

	private OwnMaterials() {
	}

	/** The compiler's block at section-relative position (x, y, z & 15): its class into this thread's grid. */
	public static void block(int x, int y, int z, BlockState state) {
		GRID.get()[(y & 15) << 8 | (z & 15) << 4 | x & 15] = 0;
	}

	/** Whether this thread is inside a section compile (OwnIntCompilerMixin brackets it). */
	public static boolean compiling() {
		return COMPILING.get()[0];
	}

	public static void compiling(boolean on) {
		COMPILING.get()[0] = on;
	}

	static byte[] grid() {
		return GRID.get();
	}
}
