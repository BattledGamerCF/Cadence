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
	/** The render thread's transformed corners (x, y, z per distinct corner). */
	@org.spongepowered.asm.mixin.Unique private static final float[] CADENCE_CORNERS = new float[3 * 32];
	/**
	 * The cube's distinct vertex positions (x, y, z each; a cube's faces share its 8 corners, each corner in 3 faces) and, for
	 * each face's vertices in order, which of them it is; null until first drawn, or when there are more than 32.
	 */
	@org.spongepowered.asm.mixin.Unique private float[] cadence$positions;
	@org.spongepowered.asm.mixin.Unique private byte[] cadence$cornerOf;
	@org.spongepowered.asm.mixin.Unique private boolean cadence$indexed;

	/**
	 * @author Cadence
	 * @reason whole-face writes into an entity-format BufferBuilder; vanilla's loop for every other consumer
	 */
	@Overwrite
	public void compile(PoseStack.Pose pose, VertexConsumer builder, int lightCoords, int overlayCoords, int color) {
		Matrix4f matrix = pose.pose();
		// (one scratch vector on the render thread: a new one per cube was ~5% of its garbage in a crowded scene)
		Vector3f scratch = com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread() ? CADENCE_SCRATCH : new Vector3f();
		// (an atlas sprite's model draws through SpriteCoordinateExpander: its UVs mapped by the same calls, then the builder)
		net.minecraft.client.renderer.texture.UvMapping mapping = null;
		VertexConsumer target = builder;
		if (builder instanceof net.minecraft.client.renderer.SpriteCoordinateExpander expander) {
			mapping = expander.mapping();
			target = expander.delegate();
		}
		if (Cpu.modelWrite && target instanceof BufferBuilder bb && cadence$fast((ModelBufferBuilderAccessor) bb)) {
			ModelBufferBuilderAccessor b = (ModelBufferBuilderAccessor) bb;
			// Each distinct corner transformed once (vanilla: once per face it's in, 24 times for 8 corners): the same
			// transformPosition on the same numbers, so the same results. Off the render thread, per vertex as before.
			byte[] cornerOf = scratch == CADENCE_SCRATCH ? this.cadence$corners() : null;
			float[] corners = CADENCE_CORNERS;
			if (cornerOf != null) {
				float[] pos = this.cadence$positions;
				for (int c = 0, n = pos.length / 3; c < n; c++) {
					Vector3f p = matrix.transformPosition(pos[c * 3], pos[c * 3 + 1], pos[c * 3 + 2], scratch);
					corners[c * 3] = p.x();
					corners[c * 3 + 1] = p.y();
					corners[c * 3 + 2] = p.z();
				}
			}
			int vertexIndex = 0;
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
				int first = vertexIndex;
				vertexIndex += count;
				if (already + count > 16777215) { // vanilla's limit: let it throw where it would
					cadence$vanillaPolygon(matrix, scratch, fx, fy, fz, vertices, builder, lightCoords, overlayCoords, color);
					continue;
				}
				long base = b.cadence$buffer().reserve(size * count);
				b.cadence$vertices(already + count);
				b.cadence$vertexPointer(base + (long) size * (count - 1));
				for (int i = 0; i < count; i++) {
					ModelPart.Vertex v = vertices[i];
					long at = base + (long) size * i;
					float px, py, pz;
					if (cornerOf != null) {
						int c = cornerOf[first + i] * 3;
						px = corners[c];
						py = corners[c + 1];
						pz = corners[c + 2];
					} else {
						Vector3f p = matrix.transformPosition(v.worldX(), v.worldY(), v.worldZ(), scratch);
						px = p.x();
						py = p.y();
						pz = p.z();
					}
					float tu = mapping == null ? v.u() : mapping.getU(v.u()), tv = mapping == null ? v.v() : mapping.getV(v.v());
					// (the same bytes as the four-byte writes, little-endian, as eight-byte pairs: half the stores; the pad byte
					// after the normal is left alone, as BufferBuilder leaves it)
					MemoryUtil.memPutLong(at, cadence$pair(Float.floatToRawIntBits(px), Float.floatToRawIntBits(py)));
					MemoryUtil.memPutLong(at + 8, cadence$pair(Float.floatToRawIntBits(pz), abgr));
					MemoryUtil.memPutLong(at + 16, cadence$pair(Float.floatToRawIntBits(tu), Float.floatToRawIntBits(tv)));
					MemoryUtil.memPutLong(at + 24, cadence$pair(overlayCoords, lightCoords));
					MemoryUtil.memPutShort(at + 32, (short) ((nx & 255) | (ny & 255) << 8));
					MemoryUtil.memPutByte(at + 34, nz);
				}
				if (Cpu.MODEL_WRITE_VERIFY) cadence$verify(b, base, size, matrix, fx, fy, fz, vertices, lightCoords, overlayCoords, color, mapping);
			}
			return;
		}
		for (ModelPart.Polygon polygon : this.polygons) {
			Vector3f n = pose.transformNormal(polygon.normal(), scratch);
			cadence$vanillaPolygon(matrix, scratch, n.x(), n.y(), n.z(), polygon.vertices(), builder, lightCoords, overlayCoords, color);
		}
	}

	/**
	 * Which distinct position each face vertex has (built once per cube; positions compared exactly, so a vertex is only ever
	 * given a corner with its own coordinates), or null when the cube has more than 32 distinct positions.
	 */
	@org.spongepowered.asm.mixin.Unique
	private byte @org.jspecify.annotations.Nullable [] cadence$corners() {
		if (this.cadence$indexed) return this.cadence$cornerOf;
		int total = 0;
		for (ModelPart.Polygon polygon : this.polygons) total += polygon.vertices().length;
		float[] pos = new float[3 * 32];
		byte[] of = new byte[total];
		int n = 0, k = 0;
		boolean ok = true;
		for (ModelPart.Polygon polygon : this.polygons) {
			for (ModelPart.Vertex v : polygon.vertices()) {
				float x = v.worldX(), y = v.worldY(), z = v.worldZ();
				int found = -1;
				for (int c = 0; c < n && found < 0; c++)
					if (Float.floatToRawIntBits(pos[c * 3]) == Float.floatToRawIntBits(x) && Float.floatToRawIntBits(pos[c * 3 + 1]) == Float.floatToRawIntBits(y)
						&& Float.floatToRawIntBits(pos[c * 3 + 2]) == Float.floatToRawIntBits(z)) found = c;
				if (found < 0) {
					if (n == 32) { ok = false; break; }
					pos[n * 3] = x;
					pos[n * 3 + 1] = y;
					pos[n * 3 + 2] = z;
					found = n++;
				}
				of[k++] = (byte) found;
			}
			if (!ok) break;
		}
		this.cadence$positions = ok ? java.util.Arrays.copyOf(pos, n * 3) : null;
		this.cadence$cornerOf = ok ? of : null;
		this.cadence$indexed = true;
		return this.cadence$cornerOf;
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
		ModelPart.Vertex[] vertices, int lightCoords, int overlayCoords, int color, net.minecraft.client.renderer.texture.@org.jspecify.annotations.Nullable UvMapping mapping) {
		try (com.mojang.blaze3d.vertex.ByteBufferBuilder bytes = new com.mojang.blaze3d.vertex.ByteBufferBuilder(size * vertices.length + 64)) {
			BufferBuilder ref = new BufferBuilder(bytes, b.cadence$topology(), b.cadence$format());
			cadence$vanillaPolygon(matrix, new Vector3f(), nx, ny, nz, vertices,
				mapping == null ? ref : new net.minecraft.client.renderer.SpriteCoordinateExpander(ref, mapping), lightCoords, overlayCoords, color);
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

	/** Two four-byte values as the eight bytes they make in memory, little-endian (first at the lower address). */
	@org.spongepowered.asm.mixin.Unique
	private static long cadence$pair(int first, int second) {
		return (first & 0xFFFFFFFFL) | (long) second << 32;
	}

	/** BufferBuilder.normalIntValue: clamp to [-1, 1], times 127, to int, low byte. */
	private static byte cadence$normal(float f) {
		return (byte) (((int) (Mth.clamp(f, -1.0F, 1.0F) * 127.0F)) & 255);
	}
}
