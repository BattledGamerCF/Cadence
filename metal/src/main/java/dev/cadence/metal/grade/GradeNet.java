package dev.cadence.metal.grade;

import java.util.Random;

/**
 * Biome Colours' network: 15 inputs (the place), 16 tanh units, 7 outputs (the grade). Trained here, on the Mac, by Adam on
 * examples made from authored looks: each vanilla biome's look (in its temperature, rain, grass and water colours), plus the
 * looks of the time of day, rain and thunder, caves and being underwater, added up for random times and weathers. Trained this
 * way the network gives a modded biome the look of the vanilla biomes it resembles, and blends smoothly between them.
 *
 * <p>Outputs: warmth (+ amber, - blue), tint (+ green, - magenta), exposure (stops x 2), saturation, contrast, shadow warmth,
 * shadow green. Each about -1..1.
 */
final class GradeNet {
	static final int IN = 15, HID = 16, OUT = 7;
	private final float[] w1 = new float[HID * IN], b1 = new float[HID], w2 = new float[OUT * HID], b2 = new float[OUT];
	float trainLoss, testLoss;

	/** The inputs, about -1..1 each. */
	static float[] input(float temperature, boolean precipitation, int grass, int water, boolean nether, boolean end, float sunHeight, float rain,
		float thunder, float skyLight, boolean underwater) {
		return new float[] {(temperature - 0.7f) / 0.8f, precipitation ? 1 : -1, ch(grass, 16), ch(grass, 8), ch(grass, 0), ch(water, 16), ch(water, 8),
			ch(water, 0), nether ? 1 : -1, end ? 1 : -1, underwater ? 1 : -1, sunHeight, rain * 2 - 1, thunder * 2 - 1, skyLight * 2 - 1};
	}

	private static float ch(int rgb, int shift) {
		return ((rgb >> shift) & 0xFF) / 127.5f - 1f;
	}

	void predict(float[] in, float[] out) {
		float[] h = new float[HID];
		forward(in, h, out);
	}

	private void forward(float[] in, float[] h, float[] out) {
		for (int j = 0; j < HID; j++) {
			float a = b1[j];
			for (int i = 0; i < IN; i++) a += w1[j * IN + i] * in[i];
			h[j] = (float) Math.tanh(a);
		}
		for (int k = 0; k < OUT; k++) {
			float a = b2[k];
			for (int j = 0; j < HID; j++) a += w2[k * HID + j] * h[j];
			out[k] = a;
		}
	}

	/** The grade as the shader's 12 floats (mcgrade.m's P): gain + exposure, shadow tint + saturation, contrast + strength. */
	static float[] toShader(float[] g, float amount) {
		float w = g[0] * amount, t = g[1] * amount;
		return new float[] {1 + 0.12f * w, 1 + 0.08f * t, 1 - 0.12f * w, (float) Math.pow(2, 0.5 * g[2] * amount), 0.04f * g[5] * amount, 0.04f * g[6] * amount,
			-0.04f * g[5] * amount, Math.max(0, 1 + g[3] * amount), Math.max(0.5f, 1 + g[4] * amount), 1, 0, 0};
	}

	// ---- the looks it learns from ----

	/** A vanilla biome: its climate and colours (as the game blends them where the biome is all around) and its look. */
	private record Place(String name, float temperature, boolean rain, int grass, int water, boolean nether, boolean end, float[] look) {
	}

	private static float[] look(float warmth, float tint, float exposure, float saturation, float contrast, float shadowWarm, float shadowGreen) {
		return new float[] {warmth, tint, exposure, saturation, contrast, shadowWarm, shadowGreen};
	}

	private static final Place[] PLACES = {
		new Place("plains", 0.8f, true, 0x91BD59, 0x3F76E4, false, false, look(0.05f, 0.02f, 0, 0.05f, 0.02f, 0, 0)),
		new Place("forest", 0.7f, true, 0x79C05A, 0x3F76E4, false, false, look(0, 0.06f, -0.02f, 0.06f, 0.04f, 0, 0.1f)),
		new Place("birch forest", 0.6f, true, 0x88BB67, 0x3F76E4, false, false, look(0.02f, 0.04f, 0.04f, 0.04f, 0.02f, 0, 0.05f)),
		new Place("dark forest", 0.7f, true, 0x507A32, 0x3F76E4, false, false, look(-0.02f, 0.08f, -0.12f, -0.05f, 0.1f, -0.1f, 0.2f)),
		new Place("desert", 2.0f, false, 0xBFB755, 0x3F76E4, false, false, look(0.4f, -0.02f, 0.1f, -0.05f, 0.08f, 0.3f, 0)),
		new Place("savanna", 2.0f, false, 0xBFB755, 0x2C8B9C, false, false, look(0.3f, 0.02f, 0.05f, 0.02f, 0.05f, 0.25f, 0)),
		new Place("badlands", 2.0f, false, 0x90814D, 0x3F76E4, false, false, look(0.5f, -0.1f, 0.05f, 0.12f, 0.1f, 0.35f, -0.05f)),
		new Place("jungle", 0.95f, true, 0x59C93C, 0x3F76E4, false, false, look(0.05f, 0.18f, -0.03f, 0.2f, 0.06f, 0, 0.3f)),
		new Place("swamp", 0.8f, true, 0x6A7039, 0x617B64, false, false, look(0.08f, 0.15f, -0.08f, -0.2f, -0.06f, 0, 0.35f)),
		new Place("mangrove swamp", 0.8f, true, 0x6A7039, 0x3A7A6A, false, false, look(0.06f, 0.14f, -0.06f, -0.12f, -0.04f, 0, 0.3f)),
		new Place("taiga", 0.25f, true, 0x86B783, 0x287082, false, false, look(-0.12f, 0.03f, 0, -0.06f, 0.04f, -0.1f, 0.05f)),
		new Place("snowy plains", 0.0f, true, 0x80B497, 0x3D57D6, false, false, look(-0.35f, -0.02f, 0.1f, -0.25f, 0.02f, -0.3f, 0)),
		new Place("frozen peaks", -0.7f, true, 0x80B497, 0x3D57D6, false, false, look(-0.45f, -0.03f, 0.12f, -0.3f, 0.05f, -0.35f, 0)),
		new Place("frozen ocean", 0.0f, true, 0x80B497, 0x3938C9, false, false, look(-0.4f, 0, 0.05f, -0.2f, 0, -0.3f, 0)),
		new Place("ocean", 0.5f, true, 0x8EB971, 0x3F76E4, false, false, look(-0.08f, 0, 0.02f, 0.05f, 0.02f, -0.1f, 0)),
		new Place("warm ocean", 0.5f, true, 0x8EB971, 0x43D5EE, false, false, look(-0.02f, 0.04f, 0.06f, 0.15f, 0.02f, -0.05f, 0.1f)),
		new Place("cherry grove", 0.5f, true, 0xB6DB61, 0x5DB7EF, false, false, look(0.05f, -0.15f, 0.06f, 0.12f, -0.02f, 0.05f, -0.15f)),
		new Place("mushroom fields", 0.9f, true, 0x55C93F, 0x3F76E4, false, false, look(0.02f, -0.18f, 0, 0.15f, 0.05f, 0, -0.2f)),
		new Place("pale garden", 0.7f, true, 0x778272, 0x76889D, false, false, look(-0.1f, -0.02f, -0.04f, -0.5f, 0.05f, -0.1f, 0)),
		new Place("meadow", 0.5f, true, 0x83BB6D, 0x0E4ECF, false, false, look(0.03f, 0.04f, 0.05f, 0.08f, 0, 0, 0.05f)),
		new Place("nether wastes", 2.0f, false, 0xBFB755, 0x3F76E4, true, false, look(0.4f, -0.06f, 0, 0.08f, 0.12f, 0.4f, -0.1f)),
		new Place("the end", 0.5f, false, 0x8EB971, 0x3F76E4, false, true, look(-0.2f, -0.2f, 0, -0.1f, 0.08f, -0.2f, -0.25f)),
	};

	/** The look of a place at a time and weather (overworld times and weather only in the overworld). */
	private static float[] lookAt(Place p, float sun, float rain, float thunder, float sky, boolean underwater) {
		float[] l = p.look.clone();
		boolean overworld = !p.nether && !p.end;
		if (overworld) {
			// night: cooler, paler; sunrise and sunset: golden
			float night = Math.clamp((-sun - 0.05f) / 0.4f, 0f, 1f);
			add(l, look(-0.3f, -0.02f, -0.05f, -0.2f, 0.02f, -0.35f, 0), night);
			float golden = Math.clamp(1 - Math.abs(sun - 0.05f) / 0.3f, 0f, 1f);
			add(l, look(0.45f, -0.05f, 0, 0.1f, 0.04f, 0.2f, -0.05f), golden);
			// rain: washed out, flatter, cooler; thunder: darker, harder
			add(l, look(-0.12f, 0, -0.1f, -0.3f, -0.12f, -0.1f, 0), rain);
			add(l, look(-0.05f, 0, -0.12f, -0.1f, 0.12f, -0.1f, 0), thunder);
			// a cave (no sky light): torchlight's warmth, a little more contrast
			add(l, look(0.12f, 0, 0, -0.08f, 0.06f, 0.1f, 0), 1 - sky);
		}
		if (underwater) add(l, look(-0.3f, 0.08f, -0.05f, -0.1f, -0.05f, -0.3f, 0.1f), 1);
		for (int i = 0; i < l.length; i++) l[i] = Math.clamp(l[i], -1f, 1f);
		return l;
	}

	private static void add(float[] l, float[] d, float k) {
		for (int i = 0; i < l.length; i++) l[i] += d[i] * k;
	}

	/** An example: a place with a little variation of its colours and temperature, a random time, weather, sky light. */
	private static void example(Random r, float[] in, float[] out) {
		Place p = PLACES[r.nextInt(PLACES.length)];
		float sun = r.nextFloat() * 2 - 1, rain = r.nextFloat() < 0.7f ? 0 : r.nextFloat(), thunder = rain > 0.5f && r.nextFloat() < 0.3f ? r.nextFloat() : 0;
		float sky = r.nextFloat() < 0.75f ? 1 : r.nextFloat();
		boolean underwater = r.nextFloat() < 0.08f;
		float[] x = input(p.temperature + (r.nextFloat() - 0.5f) * 0.1f, p.rain, jitter(r, p.grass), jitter(r, p.water), p.nether, p.end, sun, rain, thunder,
			sky, underwater);
		System.arraycopy(x, 0, in, 0, IN);
		System.arraycopy(lookAt(p, sun, rain, thunder, sky, underwater), 0, out, 0, OUT);
	}

	private static int jitter(Random r, int rgb) {
		int c = 0;
		for (int s = 0; s <= 16; s += 8) c |= Math.clamp(((rgb >> s) & 0xFF) + r.nextInt(17) - 8, 0, 255) << s;
		return c;
	}

	/** A network trained from scratch (seeded: the same network every time). */
	static GradeNet trained() {
		GradeNet n = new GradeNet();
		Random r = new Random(20261027L);
		float s1 = (float) Math.sqrt(1.0 / IN), s2 = (float) Math.sqrt(1.0 / HID);
		for (int i = 0; i < n.w1.length; i++) n.w1[i] = (float) r.nextGaussian() * s1;
		for (int i = 0; i < n.w2.length; i++) n.w2[i] = (float) r.nextGaussian() * s2;
		int samples = 4096, batch = 64, steps = 6000;
		float[][] xs = new float[samples][IN], ys = new float[samples][OUT];
		for (int i = 0; i < samples; i++) example(r, xs[i], ys[i]);
		Adam opt = new Adam(n.w1.length + n.b1.length + n.w2.length + n.b2.length);
		float[] g = new float[opt.m.length], h = new float[HID], o = new float[OUT], dh = new float[HID];
		for (int step = 0; step < steps; step++) {
			java.util.Arrays.fill(g, 0);
			for (int b = 0; b < batch; b++) {
				int k = r.nextInt(samples);
				float[] x = xs[k], y = ys[k];
				n.forward(x, h, o);
				java.util.Arrays.fill(dh, 0);
				int w2at = n.w1.length + n.b1.length, b2at = w2at + n.w2.length;
				for (int q = 0; q < OUT; q++) {
					float d = 2 * (o[q] - y[q]) / batch;
					g[b2at + q] += d;
					for (int j = 0; j < HID; j++) {
						g[w2at + q * HID + j] += d * h[j];
						dh[j] += d * n.w2[q * HID + j];
					}
				}
				for (int j = 0; j < HID; j++) {
					float d = dh[j] * (1 - h[j] * h[j]);
					g[n.w1.length + j] += d;
					for (int i = 0; i < IN; i++) g[j * IN + i] += d * x[i];
				}
			}
			opt.step(n, g, step < steps * 3 / 4 ? 3e-3f : 6e-4f);
		}
		n.trainLoss = n.loss(xs, ys);
		float[][] tx = new float[512][IN], ty = new float[512][OUT];
		for (int i = 0; i < tx.length; i++) example(r, tx[i], ty[i]);
		n.testLoss = n.loss(tx, ty);
		return n;
	}

	private float loss(float[][] xs, float[][] ys) {
		float[] h = new float[HID], o = new float[OUT];
		double s = 0;
		for (int i = 0; i < xs.length; i++) {
			forward(xs[i], h, o);
			for (int q = 0; q < OUT; q++) s += (o[q] - ys[i][q]) * (o[q] - ys[i][q]);
		}
		return (float) (s / (xs.length * OUT));
	}

	/** Adam over the weights as one vector (w1, b1, w2, b2). */
	private static final class Adam {
		final float[] m, v;
		int t;

		Adam(int n) {
			m = new float[n];
			v = new float[n];
		}

		void step(GradeNet n, float[] g, float lr) {
			t++;
			float c1 = (float) (1 - Math.pow(0.9, t)), c2 = (float) (1 - Math.pow(0.999, t));
			float[][] parts = {n.w1, n.b1, n.w2, n.b2};
			int at = 0;
			for (float[] p : parts)
				for (int i = 0; i < p.length; i++, at++) {
					m[at] = 0.9f * m[at] + 0.1f * g[at];
					v[at] = 0.999f * v[at] + 0.001f * g[at] * g[at];
					p[i] -= lr * (m[at] / c1) / ((float) Math.sqrt(v[at] / c2) + 1e-8f);
				}
		}
	}
}
