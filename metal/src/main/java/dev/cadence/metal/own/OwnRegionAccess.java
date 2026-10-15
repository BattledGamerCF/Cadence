// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/** -Dcadence.own.mesh.prefill: accessors mixed into RenderSectionRegion (and Copy into SectionCopy). */
public interface OwnRegionAccess {
	SectionCopy[] cadence$sections();

	/** -Dcadence.own.mesh.frapiRegion: the compile cache serving this region while our compile runs on it, else null. */
	@Nullable OwnRegion cadence$own();

	void cadence$own(@Nullable OwnRegion own);

	interface Copy {
		@Nullable PalettedContainer<BlockState> cadence$section();

		boolean cadence$debug();
	}
}
