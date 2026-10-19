package dev.cadence.metal.mixin.cpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.cadence.metal.cpu.Cpu;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.util.ARGB;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * Cadence (Cpu.particleWrite, -Dcadence.cpu.particleWrite=false turns it off): every quad particle is rebuilt on the CPU each
 * frame as four vertices, and vanilla does it with a new Quaternionf and four new Vector3f per particle and sixteen interface
 * calls into the vertex builder (four per vertex, each with its own element bookkeeping). When the builder is a BufferBuilder
 * writing the particle format, this reserves the four vertices at once and writes them directly, with one scratch quaternion
 * and vector on the render thread. The corners come from the same JOML calls in the same order (rotate, mul, add), the colour
 * and light from the same conversions as BufferBuilder (ARGB.toABGR, the packed light as one int), so the bytes are identical;
 * -Dcadence.cpu.particleWriteVerify=true checks that against vanilla's calls. Any other builder takes vanilla's path.
 */
@Mixin(QuadParticleRenderState.class)
abstract class ParticleQuadWriteMixin {
	@Unique private static final Quaternionf CADENCE_Q = new Quaternionf();
	@Unique private static final Vector3f CADENCE_V = new Vector3f();
	/** The particle format's offsets (Position, UV0, Color, UV2), or -1 when it isn't laid out as expected. */
	@Unique private static final int POS, UV0, COLOR, UV2, SIZE;

	static {
		VertexFormat f = DefaultVertexFormat.PARTICLE;
		int pos = -1, uv0 = -1, color = -1, uv2 = -1;
		try {
			pos = f.getElement("Position").offset();
			uv0 = f.getElement("UV0").offset();
			color = f.getElement("Color").offset();
			uv2 = f.getElement("UV2").offset();
		} catch (RuntimeException e) {
			pos = -1;
		}
		boolean ok = pos >= 0 && uv0 >= 0 && color >= 0 && uv2 >= 0 && f.getElements().size() == 4
			&& ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
		POS = ok ? pos : -1;
		UV0 = uv0;
		COLOR = color;
		UV2 = uv2;
		SIZE = f.getVertexSize();
	}

	/**
	 * @author Cadence
	 * @reason the four corners written at once into a particle-format BufferBuilder; vanilla's calls for any other builder
	 */
	@Overwrite
	protected void renderRotatedQuad(VertexConsumer builder, float x, float y, float z, float xRot, float yRot, float zRot,
		float wRot, float scale, float u0, float u1, float v0, float v1, int color, int lightCoords) {
		boolean scratch = RenderSystem.isOnRenderThread();
		Quaternionf q = scratch ? CADENCE_Q.set(xRot, yRot, zRot, wRot) : new Quaternionf(xRot, yRot, zRot, wRot);
		Vector3f v = scratch ? CADENCE_V : new Vector3f();
		if (Cpu.particleWrite && POS >= 0 && builder instanceof BufferBuilder bb && cadence$fast((ModelBufferBuilderAccessor) bb)) {
			ModelBufferBuilderAccessor b = (ModelBufferBuilderAccessor) bb;
			int already = b.cadence$vertices();
			if (already + 4 <= 16777215) { // (vanilla's limit: past it, let vanilla throw where it would)
				long base = b.cadence$buffer().reserve(SIZE * 4);
				b.cadence$vertices(already + 4);
				b.cadence$vertexPointer(base + SIZE * 3L);
				int abgr = ARGB.toABGR(color);
				cadence$put(base, q, v, x, y, z, 1.0F, -1.0F, scale, u1, v1, abgr, lightCoords);
				cadence$put(base + SIZE, q, v, x, y, z, 1.0F, 1.0F, scale, u1, v0, abgr, lightCoords);
				cadence$put(base + SIZE * 2L, q, v, x, y, z, -1.0F, 1.0F, scale, u0, v0, abgr, lightCoords);
				cadence$put(base + SIZE * 3L, q, v, x, y, z, -1.0F, -1.0F, scale, u0, v1, abgr, lightCoords);
				if (Cpu.PARTICLE_WRITE_VERIFY) cadence$verify(base, x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, lightCoords);
				return;
			}
		}
		cadence$vanilla(builder, q, v, x, y, z, scale, u0, u1, v0, v1, color, lightCoords);
	}

	/** One corner as BufferBuilder.addVertex, setUv, setColor and setLight write it. */
	@Unique
	private static void cadence$put(long at, Quaternionf q, Vector3f v, float x, float y, float z, float cx, float cy, float scale,
		float u, float vv, int abgr, int light) {
		Vector3f p = v.set(cx, cy, 0.0F).rotate(q).mul(scale).add(x, y, z);
		MemoryUtil.memPutFloat(at + POS, p.x());
		MemoryUtil.memPutFloat(at + POS + 4, p.y());
		MemoryUtil.memPutFloat(at + POS + 8, p.z());
		MemoryUtil.memPutFloat(at + UV0, u);
		MemoryUtil.memPutFloat(at + UV0 + 4, vv);
		MemoryUtil.memPutInt(at + COLOR, abgr);
		MemoryUtil.memPutInt(at + UV2, light);
	}

	/** Vanilla's renderRotatedQuad and renderVertex (with the scratch objects). */
	@Unique
	private static void cadence$vanilla(VertexConsumer builder, Quaternionf q, Vector3f v, float x, float y, float z, float scale,
		float u0, float u1, float v0, float v1, int color, int light) {
		cadence$vertex(builder, q, v, x, y, z, 1.0F, -1.0F, scale, u1, v1, color, light);
		cadence$vertex(builder, q, v, x, y, z, 1.0F, 1.0F, scale, u1, v0, color, light);
		cadence$vertex(builder, q, v, x, y, z, -1.0F, 1.0F, scale, u0, v0, color, light);
		cadence$vertex(builder, q, v, x, y, z, -1.0F, -1.0F, scale, u0, v1, color, light);
	}

	@Unique
	private static void cadence$vertex(VertexConsumer builder, Quaternionf q, Vector3f v, float x, float y, float z, float cx, float cy,
		float scale, float u, float vv, int color, int light) {
		Vector3f p = v.set(cx, cy, 0.0F).rotate(q).mul(scale).add(x, y, z);
		builder.addVertex(p.x(), p.y(), p.z()).setUv(u, vv).setColor(color).setLight(light);
	}

	/** Only BufferBuilder's plain case: building the particle format, quads, and no vertex left half-filled. */
	@Unique
	private static boolean cadence$fast(ModelBufferBuilderAccessor b) {
		return b.cadence$building() && b.cadence$elementsToFill() == 0 && b.cadence$format() == DefaultVertexFormat.PARTICLE
			&& b.cadence$topology() != PrimitiveTopology.LINES;
	}

	@Unique private static long cadence$verified, cadence$mismatches;

	/** -Dcadence.cpu.particleWriteVerify=true: the same particle through vanilla's calls into a private buffer, byte for byte. */
	@Unique
	private static void cadence$verify(long base, float x, float y, float z, float xRot, float yRot, float zRot, float wRot, float scale,
		float u0, float u1, float v0, float v1, int color, int light) {
		try (com.mojang.blaze3d.vertex.ByteBufferBuilder bytes = new com.mojang.blaze3d.vertex.ByteBufferBuilder(SIZE * 4 + 64)) {
			BufferBuilder ref = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.PARTICLE);
			cadence$vanilla(ref, new Quaternionf(xRot, yRot, zRot, wRot), new Vector3f(), x, y, z, scale, u0, u1, v0, v1, color, light);
			long refBase = ((ModelByteBufferBuilderAccessor) bytes).cadence$pointer();
			for (int o = 0; o < SIZE * 4; o++)
				if (MemoryUtil.memGetByte(refBase + o) != MemoryUtil.memGetByte(base + o)) {
					if (cadence$mismatches++ < 5) System.out.println("cadence-cpu: particleWrite MISMATCH at byte " + o);
					break;
				}
			if (++cadence$verified % 100000 == 0)
				System.out.println("cadence-cpu: particleWrite verified " + cadence$verified + " particles, " + cadence$mismatches + " mismatches");
		}
	}
}
