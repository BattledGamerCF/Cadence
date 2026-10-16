package dev.cadence.metal.mixin.cpu;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (cadence.cpu.modelWriteVerify): where a reference buffer's bytes start. */
@Mixin(ByteBufferBuilder.class)
public interface ModelByteBufferBuilderAccessor {
	@Accessor("pointer") long cadence$pointer();
}
