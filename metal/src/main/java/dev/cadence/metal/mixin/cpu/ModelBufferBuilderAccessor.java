package dev.cadence.metal.mixin.cpu;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence (cadence.cpu.model): BufferBuilder's state, for ModelCubeWriteMixin's whole-face writes. */
@Mixin(BufferBuilder.class)
public interface ModelBufferBuilderAccessor {
	@Accessor("buffer") ByteBufferBuilder cadence$buffer();
	@Accessor("building") boolean cadence$building();
	@Accessor("entityFormat") boolean cadence$entityFormat();
	@Accessor("vertexSize") int cadence$vertexSize();
	@Accessor("elementsToFill") int cadence$elementsToFill();
	@Accessor("primitiveTopology") PrimitiveTopology cadence$topology();
	@Accessor("vertices") int cadence$vertices();
	@Accessor("vertices") void cadence$vertices(int value);
	@Accessor("vertexPointer") void cadence$vertexPointer(long value);
	@Accessor("format") com.mojang.renderpearl.api.vertex.VertexFormat cadence$format();
}
