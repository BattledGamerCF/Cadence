package dev.cadence.metal.mixin.cpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.cadence.metal.cpu.Cpu;
import java.nio.ByteOrder;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Cadence (cadence.cpu.model; Cpu.modelWrite, -Dcadence.cpu.modelWrite=false turns it off): items (dropped, held, in frames)
 * and block models drawn as items are baked quads, and VertexConsumer.putBakedQuad writes each of their four vertices with a
 * new position vector and an interface call that does the start-of-vertex bookkeeping, reserves the vertex, and turns the
 * normal into bytes again. With the crowded test scene's dropped items that was ~10% of the render thread. Here a
 * BufferBuilder writing the entity format writes the quad at once: one reservation, the normal's bytes once. Every value
 * comes from the same calls in the same order as the default method (the same JOML transforms, QuadInstance colour and
 * light, UVPair unpacking, BufferBuilder's colour and normal conversions), so the bytes are identical
 * (-Dcadence.cpu.modelWriteVerify=true checks against the default method); anything else goes to the default method.
 */
@Mixin(BufferBuilder.class)
abstract class ModelQuadWriteMixin implements VertexConsumer {
	@Shadow @Final private ByteBufferBuilder buffer;
	@Shadow private long vertexPointer;
	@Shadow private int vertices;
	@Shadow @Final private PrimitiveTopology primitiveTopology;
	@Shadow @Final private boolean entityFormat;
	@Shadow @Final private int vertexSize;
	@Shadow private int elementsToFill;
	@Shadow private boolean building;
	@Unique private static final Vector3f CADENCE_NORMAL = new Vector3f(), CADENCE_POSITION = new Vector3f();
	@Unique private static final boolean CADENCE_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

	@Override
	public void putBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
		if (!Cpu.modelWrite || !this.building || !this.entityFormat || this.elementsToFill != 0 || this.primitiveTopology == PrimitiveTopology.LINES
			|| !CADENCE_LITTLE_ENDIAN || this.vertices + 4 > 16777215 || !RenderSystem.isOnRenderThread()) {
			if (Cpu.MODEL_WRITE_VERIFY && Cpu.modelWrite && !cadence$toldFallback) {
				cadence$toldFallback = true;
				System.out.println("cadence-cpu: quadWrite first fallback: building " + this.building + ", entity format " + this.entityFormat
					+ ", elementsToFill " + this.elementsToFill + ", topology " + this.primitiveTopology + ", render thread " + RenderSystem.isOnRenderThread());
			}
			VertexConsumer.super.putBakedQuad(pose, quad, instance);
			return;
		}
		Matrix4f matrix = pose.pose();
		Vector3f n = pose.transformNormal(quad.direction().getUnitVec3f(), CADENCE_NORMAL);
		byte nx = cadence$normal(n.x()), ny = cadence$normal(n.y()), nz = cadence$normal(n.z());
		int emission = quad.materialInfo().lightEmission();
		int overlay = instance.overlayCoords();
		int size = this.vertexSize;
		long base = this.buffer.reserve(size * 4);
		this.vertices += 4;
		this.vertexPointer = base + size * 3L;
		for (int i = 0; i < 4; i++) {
			long uv = quad.packedUV(i);
			int color = instance.getColor(i);
			int light = instance.getLightCoordsWithEmission(i, emission);
			Vector3f p = matrix.transformPosition(quad.position(i), CADENCE_POSITION);
			long at = base + (long) size * i;
			MemoryUtil.memPutFloat(at, p.x());
			MemoryUtil.memPutFloat(at + 4, p.y());
			MemoryUtil.memPutFloat(at + 8, p.z());
			MemoryUtil.memPutInt(at + 12, ARGB.toABGR(color));
			MemoryUtil.memPutFloat(at + 16, UVPair.unpackU(uv));
			MemoryUtil.memPutFloat(at + 20, UVPair.unpackV(uv));
			MemoryUtil.memPutInt(at + 24, overlay);
			MemoryUtil.memPutInt(at + 28, light);
			MemoryUtil.memPutByte(at + 32, nx);
			MemoryUtil.memPutByte(at + 33, ny);
			MemoryUtil.memPutByte(at + 34, nz);
		}
		if (Cpu.MODEL_WRITE_VERIFY) cadence$verify(base, size, pose, quad, instance);
	}

	/** BufferBuilder.normalIntValue: clamp to [-1, 1], times 127, to int, low byte. */
	@Unique
	private static byte cadence$normal(float f) {
		return (byte) (((int) (Mth.clamp(f, -1.0F, 1.0F) * 127.0F)) & 255);
	}

	@Unique private static long cadence$verified, cadence$mismatches;
	@Unique private static boolean cadence$toldFallback;

	/** -Dcadence.cpu.modelWriteVerify=true: the same quad through the default method into a private buffer, byte for byte. */
	@Unique
	private void cadence$verify(long base, int size, PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
		try (ByteBufferBuilder bytes = new ByteBufferBuilder(size * 4 + 64)) {
			BufferBuilder ref = new BufferBuilder(bytes, this.primitiveTopology, ((ModelBufferBuilderAccessor) this).cadence$format());
			// (the default method on a builder of the same kind; this mixin's override would answer for it, so it is off meanwhile)
			Cpu.modelWrite = false;
			try {
				ref.putBakedQuad(pose, quad, instance);
			} finally {
				Cpu.modelWrite = true;
			}
			long refBase = ((ModelByteBufferBuilderAccessor) bytes).cadence$pointer();
			for (int i = 0; i < 4; i++)
				for (int o = 0; o < 35; o++)
					if (MemoryUtil.memGetByte(refBase + (long) size * i + o) != MemoryUtil.memGetByte(base + (long) size * i + o)) {
						if (cadence$mismatches++ < 5) System.out.println("cadence-cpu: quadWrite MISMATCH at byte " + o + " of vertex " + i);
						break;
					}
			if (++cadence$verified % 200000 == 0 || cadence$verified == 1000 || cadence$verified == 10000)
				System.out.println("cadence-cpu: quadWrite verified " + cadence$verified + " quads, " + cadence$mismatches + " mismatches");
		}
	}
}
