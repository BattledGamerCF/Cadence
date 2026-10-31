package dev.cadence.metal.neural;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A Style Filter network made fit for the Neural Engine. Create ML's style transfer pads with reflection before each
 * convolution and scales its output by 255 with a Scale layer; the Neural Engine runs neither, so Core ML split the network
 * into 17 pieces with a trip to the CPU between each (28.7 ms a picture on an M2). Here the paddings become zero paddings and
 * the scale a linear activation, so all of it runs on the Neural Engine, and the picture's size is set to the window's shape
 * (512 wide) instead of a 512 x 512 square: 8.5 ms. Zero padding darkens the picture's edges, so the input gets a margin of
 * mirrored pixels (MARGIN on each side, mcneural.m nn_down) that's cut off again when the result is drawn.
 *
 * <p>Only the few fields this needs are read from the Core ML model (a protobuf): Model.description (2) > input (1) / output (10)
 * > type (3) > imageType (4) > width (1), height (2); Model.neuralNetwork (500) > layers (1) > padding (200) > reflection (2) /
 * scale (245). Anything else is copied as it is.
 */
final class StyleSpec {
	/** Mirrored pixels on each side of the model's input (a multiple of 4: the network halves the picture twice). */
	static final int MARGIN = 16;
	static final int WIDTH = 512;

	private StyleSpec() {
	}

	/** The model's input and output size for a window w x h: 512 wide plus the margins, the height to the window's shape. */
	static int[] size(int windowW, int windowH) {
		int h = Math.round(WIDTH * (float) windowH / Math.max(1, windowW) / 4f) * 4;
		h = Math.clamp(h, 128, 512);
		return new int[] {WIDTH + 2 * MARGIN, h + 2 * MARGIN};
	}

	/** The model at src rewritten into dst for a w x h picture; false if it isn't the network this expects (src is used as is). */
	static boolean rewrite(Path src, Path dst, int w, int h) throws Exception {
		List<Field> model = parse(Files.readAllBytes(src));
		Field nn = first(model, 500), desc = first(model, 2);
		if (nn == null || desc == null) return false;
		int pads = 0, scales = 0;
		List<Field> layers = nn.parsed();
		for (Field layer : layers) {
			if (layer.number != 1) continue;
			for (Field f : layer.parsed()) {
				if (f.number == 200) {
					for (Field p : f.parsed()) {
						if (p.number == 2) {
							// reflection (an empty message) -> constant (value 0: an empty message too)
							p.number = 1;
							pads++;
						}
					}
				} else if (f.number == 245) {
					// Scale (x255 per channel, no bias) -> ActivationParams.linear (5) { alpha (1) = 255, beta (2) = 0 }
					float[] scale = floats(first(f.parsed(), 2));
					float[] bias = floats(first(f.parsed(), 5));
					float s = scale.length > 0 ? scale[0] : 1f;
					for (float v : scale) if (v != s) return false;
					for (float v : bias) if (v != 0f) return false;
					Field linear = Field.message(5, List.of(Field.fixed32(1, s), Field.fixed32(2, 0f)));
					f.number = 130;
					f.children = new ArrayList<>(List.of(linear));
					scales++;
				}
			}
		}
		if (pads == 0) return false;
		int images = 0;
		for (Field io : desc.parsed()) {
			if (io.number != 1 && io.number != 10) continue;
			Field type = first(io.parsed(), 3);
			if (type == null) continue;
			for (Field t : type.parsed()) {
				if (t.number != 4) continue;
				// imageType: width, height, colour space kept; size ranges / enumerated sizes dropped (the fixed size stands)
				List<Field> kept = new ArrayList<>();
				for (Field e : t.parsed()) {
					if (e.number == 1) kept.add(Field.varint(1, w));
					else if (e.number == 2) kept.add(Field.varint(2, h));
					else if (e.number == 3) kept.add(e);
				}
				t.children = kept;
				images++;
			}
		}
		if (images != 2) return false;
		Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
		Files.write(tmp, serialize(model));
		Files.move(tmp, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		System.out.println("[cadence] style filter: network fitted for the Neural Engine (" + pads + " paddings, " + scales + " scale, " + w + "x" + h + ")");
		return true;
	}

	private static float[] floats(Field weights) {
		// WeightParams.floatValue (1): packed floats
		if (weights == null) return new float[0];
		Field values = first(weights.parsed(), 1);
		if (values == null) return new float[0];
		ByteBuffer b = ByteBuffer.wrap(values.bytes).order(ByteOrder.LITTLE_ENDIAN);
		float[] out = new float[values.bytes.length / 4];
		for (int i = 0; i < out.length; i++) out[i] = b.getFloat();
		return out;
	}

	private static Field first(List<Field> fields, int number) {
		for (Field f : fields) if (f.number == number) return f;
		return null;
	}

	/** One protobuf field: a varint, fixed bytes, or length-delimited bytes (parsed into fields only when asked). */
	private static final class Field {
		int number, wire;
		long value;
		byte[] bytes;
		List<Field> children;

		static Field varint(int number, long v) {
			Field f = new Field();
			f.number = number;
			f.value = v;
			return f;
		}

		static Field fixed32(int number, float v) {
			Field f = new Field();
			f.number = number;
			f.wire = 5;
			f.bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array();
			return f;
		}

		static Field message(int number, List<Field> children) {
			Field f = new Field();
			f.number = number;
			f.wire = 2;
			f.children = new ArrayList<>(children);
			return f;
		}

		List<Field> parsed() {
			if (children == null) children = parse(bytes);
			return children;
		}
	}

	private static List<Field> parse(byte[] b) {
		List<Field> out = new ArrayList<>();
		int[] i = {0};
		while (i[0] < b.length) {
			long key = varint(b, i);
			Field f = new Field();
			f.number = (int) (key >>> 3);
			f.wire = (int) (key & 7);
			switch (f.wire) {
				case 0 -> f.value = varint(b, i);
				case 1, 5 -> {
					int n = f.wire == 1 ? 8 : 4;
					f.bytes = java.util.Arrays.copyOfRange(b, i[0], i[0] + n);
					i[0] += n;
				}
				case 2 -> {
					int n = (int) varint(b, i);
					f.bytes = java.util.Arrays.copyOfRange(b, i[0], i[0] + n);
					i[0] += n;
				}
				default -> throw new IllegalStateException("wire type " + f.wire);
			}
			out.add(f);
		}
		return out;
	}

	private static long varint(byte[] b, int[] i) {
		long r = 0;
		for (int s = 0;; s += 7) {
			int c = b[i[0]++] & 0xff;
			r |= (long) (c & 0x7f) << s;
			if (c < 0x80) return r;
		}
	}

	private static void putVarint(ByteArrayOutputStream o, long v) {
		while ((v & ~0x7fL) != 0) {
			o.write((int) (v & 0x7f) | 0x80);
			v >>>= 7;
		}
		o.write((int) v);
	}

	private static byte[] serialize(List<Field> fields) {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		for (Field f : fields) {
			putVarint(o, (long) f.number << 3 | f.wire);
			switch (f.wire) {
				case 0 -> putVarint(o, f.value);
				case 2 -> {
					byte[] body = f.children != null ? serialize(f.children) : f.bytes;
					putVarint(o, body.length);
					o.writeBytes(body);
				}
				default -> o.writeBytes(f.bytes);
			}
		}
		return o.toByteArray();
	}
}
