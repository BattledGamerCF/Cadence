// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.chunk;

import java.util.Arrays;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** C1: one shared, never-written array of 4096 AIR states for every empty section a LevelSlice maps (mesh=air). */
public final class SliceArrays {
	private SliceArrays() { }

	public static final BlockState[] AIR = new BlockState[4096];

	static {
		Arrays.fill(AIR, Blocks.AIR.defaultBlockState());
	}
}
