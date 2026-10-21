package dev.cadence.metal.mixin.gen;

import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Cadence (on by default; -Dcadence.gen.bufferAge=20 is vanilla): world generation samples noise into DensityBuffers (float arrays up to
 * ~140 KB) from pools that RandomState lends to each chunk being generated (up to 16 pools kept). A pool drops a buffer that
 * went unused for 20 of its clean-ups, after which the next chunk allocates a new one. Those arrays were 89% of what young
 * garbage collections copied while flying over new terrain: the generation working set, freshly allocated, survives a
 * collection or two and is copied each time. A longer age keeps the buffers (old-gen residents, copied once), with the same
 * pool rules otherwise, so the values sampled are the same. Measured (ABBA, 4-minute flights over new terrain, NIGHT-5.md):
 * young collections 639 / 570 -> 427 / 386, their pause time 1,906 / 1,828 -> 1,374 / 1,409 ms (-26%), marking cycles 37 / 34
 * -> 16 / 15; heap used +~20 MB (at most 16 pools, each holding what one chunk's generation uses at once).
 */
@Mixin(RandomState.class)
abstract class GenBufferPoolMixin {
	@ModifyConstant(method = "acquireDensityBufferPool", constant = @Constant(intValue = 20))
	private int cadence$bufferAge(int vanilla) {
		return GenMixinPlugin.BUFFER_AGE;
	}
}
