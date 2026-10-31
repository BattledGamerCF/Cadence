package dev.cadence.metal.grade;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.cadence.metal.backend.MetalBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * Biome Colours (Video Settings > Cadence: Visuals): the picture colour graded for where you are, as a film is graded scene by
 * scene: warm and bright in a desert, cool and pale in the snow, green and lush in a jungle, grey-green in a swamp, golden at
 * sunset, blue at night, washed out in the rain, red in the Nether, violet in the End. A tiny neural network (GradeNet: 15
 * inputs, 16 hidden, 7 outputs) turns what it knows about the place (the biome's temperature and whether it rains there, the
 * grass and water colours blended around you, the dimension, the sun's height, rain and thunder, sky light, underwater) into
 * a grade: white balance, tint, exposure, saturation, contrast and the shadows' tint. It is trained on this Mac when first
 * turned on (a fraction of a second, off the render thread) from looks authored for the vanilla biomes, so a modded biome gets
 * the grade of the vanilla ones its colours and climate are like. Four times a second it is asked again; the grade eases to
 * the new one over a few seconds. The grade itself is one full-screen pass over the world (mcgrade.m), before the hand and the
 * HUD. Not with a shader pack (it grades its own picture).
 */
public final class BiomeGrade {
	public enum Strength {
		OFF("Off", 0f), SUBTLE("Subtle", 0.5f), NORMAL("Normal", 1f), STRONG("Strong", 1.6f);

		public final String label;
		final float amount;

		Strength(String label, float amount) {
			this.label = label;
			this.amount = amount;
		}
	}

	private static volatile Strength strength = Strength.OFF;
	private static volatile GradeNet net;
	private static volatile boolean training, failed;
	/** The level the grade was last asked in: another one (a world, a dimension) starts at its own grade, not eased from the old. */
	private static java.lang.ref.WeakReference<Object> askedIn = new java.lang.ref.WeakReference<>(null);
	private static long ctx, enc, params;
	private static long askedAt;
	/** The grade being shown (eased) and the one asked for, as the network's 7 outputs. */
	private static final float[] shown = new float[GradeNet.OUT], target = new float[GradeNet.OUT];
	private static boolean first = true;
	private static long lastFrame;
	private static volatile String last = "";

	private BiomeGrade() {
	}

	public static Strength strength() {
		return strength;
	}

	public static void setStrength(Strength s) {
		strength = s == null ? Strength.OFF : s;
	}

	/** The last grade asked for, for the self-test. */
	public static String last() {
		return last;
	}

	/** Render thread, after the world is drawn (before the hand and the HUD). color null: not this frame (a shader pack). */
	public static void afterWorld(GpuTexture color) {
		Strength s = strength;
		if (s == Strength.OFF || color == null || failed) {
			first = true;
			return;
		}
		try {
			frame(color, s);
		} catch (RuntimeException | LinkageError e) {
			// (once: a broken pass or a stale native library isn't retried every frame, BH8-14)
			failed = true;
			System.out.println("[cadence] biome colours: off after an error: " + e);
		}
	}

	private static void frame(GpuTexture color, Strength s) {
		GradeNet n = net;
		if (n == null) {
			train();
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ClientLevel lv = mc.level;
		if (lv == null || mc.gameRenderer == null) return;
		if (askedIn.get() != lv) {
			askedIn = new java.lang.ref.WeakReference<>(lv);
			first = true;
		}
		long now = System.nanoTime();
		if (now - askedAt > 250_000_000L || first) {
			askedAt = now;
			float[] in = features(mc, lv);
			n.predict(in, target);
			last = describe(in, target);
		}
		// eased: about 3 s to settle (and at once on the first frame)
		float dt = first ? 1f : Math.min(1f, (now - lastFrame) / 1e9f / 1.2f);
		lastFrame = now;
		for (int i = 0; i < shown.length; i++) shown[i] += (target[i] - shown[i]) * (first ? 1f : dt);
		first = false;
		encode(color, s.amount);
	}

	/** The place, as the network sees it (GradeNet's inputs). */
	static float[] features(Minecraft mc, ClientLevel lv) {
		Vec3 eye = mc.gameRenderer.mainCamera().position();
		BlockPos pos = BlockPos.containing(eye);
		Biome biome = lv.getBiome(pos).value();
		int grass = BiomeColors.getAverageGrassColor(lv, pos), water = BiomeColors.getAverageWaterColor(lv, pos);
		boolean nether = lv.dimension() == Level.NETHER, end = lv.dimension() == Level.END;
		double t = (lv.getDefaultClockTime() % 24000L) / 24000.0;
		float sun = nether || end ? 0f : (float) Math.cos(2 * Math.PI * (t - 0.25));
		float sky = nether || end ? 1f : lv.getBrightness(LightLayer.SKY, pos) / 15f;
		boolean underwater = mc.gameRenderer.mainCamera().getFluidInCamera() == FogType.WATER;
		return GradeNet.input(biome.getBaseTemperature(), biome.hasPrecipitation(), grass, water, nether, end, sun, lv.getRainLevel(1f),
			lv.getThunderLevel(1f), sky, underwater);
	}

	private static void encode(GpuTexture color, float amount) {
		if (ctx == 0) {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			ctx = MetalBridge.ctx(encoder);
			enc = MetalBridge.enc(encoder);
			params = MemoryUtil.nmemAlloc(12 * 4);
		}
		float[] p = GradeNet.toShader(shown, amount);
		for (int i = 0; i < 12; i++) MemoryUtil.memPutFloat(params + i * 4L, p[i]);
		GradeNative.frame(ctx, enc, MetalBridge.textureHandle(color), params);
	}

	/** The network trained off the render thread (the picture ungraded until it's ready, a fraction of a second). */
	private static void train() {
		if (training) return;
		training = true;
		Thread t = new Thread(() -> {
			try {
				long t0 = System.nanoTime();
				GradeNet n = GradeNet.trained();
				System.out.println(String.format(java.util.Locale.ROOT, "[cadence] biome colours: network trained in %.0f ms (loss %.4f, held-out %.4f)",
					(System.nanoTime() - t0) / 1e6, n.trainLoss, n.testLoss));
				net = n;
			} catch (Throwable e) {
				failed = true;  // (said, not waiting for a network forever, BH8-14)
				System.out.println("[cadence] biome colours: training failed: " + e);
			}
		}, "cadence-grade-train");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	private static String describe(float[] in, float[] out) {
		return String.format(java.util.Locale.ROOT, "temp %.2f sun %.2f rain %.2f sky %.2f -> warmth %.2f tint %.2f exposure %.2f saturation %.2f contrast %.2f shadows %.2f/%.2f",
			in[0], in[11], in[12], in[14], out[0], out[1], out[2], out[3], out[4], out[5], out[6]);
	}
}
