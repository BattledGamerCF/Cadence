package dev.cadence.metal.mixin.logic;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** SkySources: the sky-source map's own setters and edge test. */
@Mixin(ChunkSkyLightSources.class)
public interface SkySourcesAccess {
	@Accessor("minY")
	int cadence$minY();

	@Invoker("set")
	void cadence$set(int index, int y);

	@Invoker("fill")
	void cadence$fill(int y);

	@Invoker("isEdgeOccluded")
	static boolean cadence$isEdgeOccluded(BlockState above, BlockState below) {
		throw new AssertionError();
	}
}
