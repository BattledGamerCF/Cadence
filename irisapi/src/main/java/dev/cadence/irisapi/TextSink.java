package dev.cadence.irisapi;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntFunction;
import net.irisshaders.iris.api.v0.IrisTextVertexSink;

/**
 * The Iris API's text vertex sink: glyph quads as the game's POSITION_COLOR_TEX_LIGHTMAP vertices (Cadence's packs read the
 * game's own text format; there is no extended Iris format to fill). Color is ARGB, light the packed block/sky pair.
 */
final class TextSink implements IrisTextVertexSink {
	private static final VertexFormat FORMAT = DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP;
	private final ByteBuffer buffer;

	TextSink(int maxQuadCount, IntFunction<ByteBuffer> bufferProvider) {
		buffer = bufferProvider.apply(maxQuadCount * 4 * FORMAT.getVertexSize()).order(ByteOrder.nativeOrder());
	}

	@Override
	public VertexFormat getUnderlyingVertexFormat() {
		return FORMAT;
	}

	@Override
	public ByteBuffer getUnderlyingByteBuffer() {
		return buffer;
	}

	@Override
	public void quad(float x1, float y1, float x2, float y2, float z, int color, float u1, float v1, float u2, float v2, int light) {
		// (the game's glyph order: top left, bottom left, bottom right, top right)
		vertex(x1, y1, z, color, u1, v1, light);
		vertex(x1, y2, z, color, u1, v2, light);
		vertex(x2, y2, z, color, u2, v2, light);
		vertex(x2, y1, z, color, u2, v1, light);
	}

	private void vertex(float x, float y, float z, int argb, float u, float v, int light) {
		buffer.putFloat(x).putFloat(y).putFloat(z);
		buffer.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
		buffer.putFloat(u).putFloat(v);
		buffer.putShort((short) (light & 0xFFFF)).putShort((short) (light >>> 16));
	}
}
