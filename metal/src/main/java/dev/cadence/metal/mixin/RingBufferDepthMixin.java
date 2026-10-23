package dev.cadence.metal.mixin;

import net.minecraft.client.renderer.MappableRingBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Cadence: the game's per-frame rings (MappableRingBuffer: the GUI's vertex staging, the dynamic transforms, fog, lightmap,
 * clouds) five deep instead of three, so Render Max FPS's frames can overlap further (MetalEncoder.MAX_FPS_IN_FLIGHT): with three
 * slots, a frame waited for the one three back, half of every frame at 12,000 fps. -Dcadence.metal.ringDepth (3: the game's).
 */
@Mixin(MappableRingBuffer.class)
abstract class RingBufferDepthMixin {
	private static final int CADENCE_DEPTH = Math.max(3, Integer.getInteger("cadence.metal.ringDepth", 5));

	@ModifyConstant(method = "*", constant = @Constant(intValue = 3))
	private int cadence$depth(int three) {
		return CADENCE_DEPTH;
	}
}
