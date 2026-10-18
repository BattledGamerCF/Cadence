package dev.cadence.metal.mixin.cpu;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.cadence.metal.cpu.Cpu;
import java.nio.ByteOrder;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Cadence (cadence.cpu.model; Cpu.modelWrite, -Dcadence.cpu.modelWrite=false turns it off): entity models are rebuilt on the
 * CPU every frame, vertex by vertex, and that was ~45% of the render thread without shaders in a scene with a few mobs.
 * Vanilla makes one interface call per vertex (megamorphic: many consumer types), does the start-of-vertex bookkeeping and
 * a buffer reservation per vertex, and turns the face normal into bytes once per vertex. When the consumer is a
 * BufferBuilder writing the entity format, this writes a whole face at once: one reservation and bookkeeping step for its
 * vertices, the normal's bytes computed once per face. Every value comes from the same calls in the same order (the same JOML
 * transforms, the same byte conversion as BufferBuilder.normalIntValue), so the bytes written are identical; any other
 * consumer, or a builder in an unusual state, takes vanilla's path unchanged.
 */
@Mixin(ModelPart.Cube.class)
abstract class ModelCubeWriteMixin {
	@Shadow @Final public ModelPart.Polygon[] polygons;
	@org.spongepowered.asm.mixin.Unique private static final Vector3f CADENCE_SCRATCH = new Vector3f();

	/**
	 * @author Cadence
	 * @reason whole-face writes into an entity-format BufferBuilder; vanilla's loop for every other consumer
	 */
	@Overwrite
	public void compile(PoseStack.Pose pose, VertexConsumer builder, int lightCoords, int overlayCoords, int color) {
		Matrix4f matrix = pose.pose();
		// (one scratch vector on the render thread: a new one per cube was ~5% of its garbage in a crowded scene)
		Vector3f scratch = com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread() ? CADENCE_SCRATCH : new Vector3f();
		if (Cpu.modelWrite && builder instanceof BufferBuilder bb && cadence$fast((ModelBufferBuilderAccessor) bb)) {
			ModelBufferBuilderAccessor b = (ModelBufferBuilderAccessor) bb;
			int size = b.cadence$vertexSize();
			int abgr = ARGB.toABGR(color);
			for (ModelPart.Polygon polygon : this.polygons) {
				Vector3f n = pose.transformNormal(polygon.normal(), scratch);
				float fx = n.x(), fy = n.y(), fz = n.z(); // (scratch is reused for the positions below)
				byte nx = cadence$normal(fx), ny = cadence$normal(fy), nz = cadence$normal(fz);
				ModelPart.Vertex[] vertices = polygon.vertices();
				int count = vertices.length;
				if (count == 0) continue;
				int already = b.cadence$vertices();
				if (already + count > 16777215) { // vanilla's limit: let it throw where it would
					cadence$vanillaPolygon(matrix, scratch, fx, fy, fz, vertices, builder, lightCoords, overlayCoords, color);
					continue;
				}
				long base = b.cadence$buffer().reserve(size * count);
				b.cadence$vertices(already + count);
				b.cadence$vertexPointer(base + (long) size * (count - 1));
				for (int i = 0; i < count; i++) {
					ModelPart.Vertex v = vertices[i];
					Vector3f p = matrix.transformPosition(v.worldX(), v.worldY(), v.worldZ(), scratch);
					long at = base + (long) size * i;
					MemoryUtil.memPutFloat(at, p.x());
					MemoryUtil.memPutFloat(at + 4, p.y());
					MemoryUtil.memPutFloat(at + 8, p.z());
					MemoryUtil.memPutInt(at + 12, abgr);
					MemoryUtil.memPutFloat(at + 16, v.u());
					MemoryUtil.memPutFloat(at + 20, v.v());
					MemoryUtil.memPutInt(at + 24, overlayCoords);
					MemoryUtil.memPutInt(at + 28, lightCoords);
					MemoryUtil.memPutByte(at + 32, nx);
					MemoryUtil.memPutByte(at + 33, ny);
					MemoryUtil.memPutByte(at + 34, nz);
				}
				if (Cpu.MODEL_WRITE_VERIFY) cadence$verify(b, base, size, matrix, fx, fy, fz, vertices, lightCoords, overlayCoords, color);
			}
			return;
		}
		for (ModelPart.Polygon polygon : this.polygons) {
			Vector3f n = pose.transformNormal(polygon.normal(), scratch);
			cadence$vanillaPolygon(matrix, scratch, n.x(), n.y(), n.z(), polygon.vertices(), builder, lightCoords, overlayCoords, color);
		}
	}

	/** Vanilla's per-vertex loop for one face (the normal already transformed). */
	private static void cadence$vanillaPolygon(Matrix4f matrix, Vector3f scratch, float nx, float ny, float nz, ModelPart.Vertex[] vertices,
		VertexConsumer builder, int lightCoords, int overlayCoords, int color) {
		for (ModelPart.Vertex v : vertices) {
			Vector3f p = matrix.transformPosition(v.worldX(), v.worldY(), v.worldZ(), scratch);
			builder.addVertex(p.x(), p.y(), p.z(), color, v.u(), v.v(), overlayCoords, lightCoords, nx, ny, nz);
		}
	}

	/**
	 * The fast path writes what BufferBuilder.addVertex's entity branch writes, so only for that branch: building, entity
	 * format, little-endian (its RGBA and UV writes), not lines, and no vertex left half-filled (vanilla would throw there).
	 */
	private static boolean cadence$fast(ModelBufferBuilderAccessor b) {
		return b.cadence$building() && b.cadence$entityFormat() && b.cadence$elementsToFill() == 0
			&& b.cadence$topology() != PrimitiveTopology.LINES && LITTLE_ENDIAN;
	}

	private static long cadence$verified, cadence$mismatches;

	/**
	 * -Dcadence.cpu.modelWriteVerify=true: the same face through vanilla's per-vertex calls into a private buffer, compared
	 * byte for byte with what was just written (the padding byte after the normal excepted: neither path writes it).
	 */
	private static void cadence$verify(ModelBufferBuilderAccessor b, long base, int size, Matrix4f matrix, float nx, float ny, float nz,
		ModelPart.Vertex[] vertices, int lightCoords, int overlayCoords, int color) {
		try (com.mojang.blaze3d.vertex.ByteBufferBuilder bytes = new com.mojang.blaze3d.vertex.ByteBufferBuilder(size * vertices.length + 64)) {
			BufferBuilder ref = new BufferBuilder(bytes, b.cadence$topology(), b.cadence$format());
			cadence$vanillaPolygon(matrix, new Vector3f(), nx, ny, nz, vertices, ref, lightCoords, overlayCoords, color);
			long refBase = ((ModelByteBufferBuilderAccessor) bytes).cadence$pointer();
			for (int i = 0; i < vertices.length; i++)
				for (int o = 0; o < 35; o++)
					if (MemoryUtil.memGetByte(refBase + (long) size * i + o) != MemoryUtil.memGetByte(base + (long) size * i + o)) {
						if (cadence$mismatches++ < 5) System.out.println("cadence-cpu: modelWrite MISMATCH at byte " + o + " of vertex " + i);
						break;
					}
			if (++cadence$verified % 200000 == 0)
				System.out.println("cadence-cpu: modelWrite verified " + cadence$verified + " faces, " + cadence$mismatches + " mismatches");
		}
	}

	private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

	/** BufferBuilder.normalIntValue: clamp to [-1, 1], times 127, to int, low byte. */
	private static byte cadence$normal(float f) {
		return (byte) (((int) (Mth.clamp(f, -1.0F, 1.0F) * 127.0F)) & 255);
	}
}
