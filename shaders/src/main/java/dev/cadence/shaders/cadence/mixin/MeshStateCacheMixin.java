package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.mesh.MeshStateCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Cadence (MeshStateCache): the region's block states for the meshed section and a one-block border, read once each. */
@Mixin(RenderSectionRegion.class)
public abstract class MeshStateCacheMixin implements MeshStateCache.Holder {
	@Unique private static final int SPAN = 18;
	@Shadow @Final private int minSectionX;
	@Shadow @Final private int minSectionY;
	@Shadow @Final private int minSectionZ;
	@Unique private BlockState[] cadence$states;

	@Override
	public void cadence$dropStates() {
		cadence$states = null;
	}

	@Redirect(method = {"getBlockState", "getFluidState"}, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/chunk/SectionCopy;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState cadence$cached(SectionCopy copy, BlockPos pos) {
		// the border's first block: the centre section's minimum corner minus one (RADIUS = 1)
		int x = pos.getX() - ((minSectionX + 1) << 4) + 1, y = pos.getY() - ((minSectionY + 1) << 4) + 1,
			z = pos.getZ() - ((minSectionZ + 1) << 4) + 1;
		if (!MeshStateCache.enabled || (x | y | z) < 0 || x >= SPAN || y >= SPAN || z >= SPAN) return copy.getBlockState(pos);
		BlockState[] states = cadence$states;
		if (states == null) cadence$states = states = new BlockState[SPAN * SPAN * SPAN];
		int i = (y * SPAN + z) * SPAN + x;
		BlockState state = states[i];
		if (state == null) states[i] = state = copy.getBlockState(pos);
		else if (MeshStateCache.VERIFY) MeshStateCache.verify(state, copy.getBlockState(pos));
		return state;
	}
}
