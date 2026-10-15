package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.ChunkHeaps;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Cadence: chunk uber-buffer heap sizes (ChunkHeaps) instead of vanilla's 128 MB / 32 MB. */
@Mixin(SectionRenderDispatcher.class)
public abstract class ChunkHeapSizeMixin {
	@ModifyConstant(method = "lambda$new$0", constant = @Constant(intValue = 134217728))
	private int cadence$vertexHeap(int vanilla) {
		return ChunkHeaps.vertexBytes();
	}

	@ModifyConstant(method = "lambda$new$0", constant = @Constant(intValue = 33554432))
	private int cadence$indexHeap(int vanilla) {
		return ChunkHeaps.indexBytes();
	}
}
