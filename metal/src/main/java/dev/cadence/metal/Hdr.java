package dev.cadence.metal;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * HDR (Video Settings > Cadence: Visuals > HDR): the picture on the display's extended dynamic range. With it on, the game's frame
 * (the main target and Cadence's scaled world target) is an RGBA16Float texture instead of RGBA8: it holds the same sRGB-encoded
 * values the game always wrote, but light sources may go above 1.0 (Cadence's terrain renderer lifts emissive blocks, the
 * sky renderer draws the sun brighter, see {@link #emissiveGain()}), and the window's layer is extended linear sRGB with EDR on.
 * The present (mcmetal.m present_hdr_fs) is the tone mapping: up to the knee everything passes unchanged, so the GUI and every
 * ordinary surface look exactly as in SDR (1.0 is the display's SDR white); above it, by the brightest channel, values roll off
 * into the headroom macOS reports for the game's screen right now (NSScreen maximumExtendedDynamicRangeColorComponentValue,
 * read every quarter second: it follows the brightness setting, and is 1.0 at full brightness). Readers that want 8 bits
 * (screenshots, copies into 8-bit targets) get the frame with highlights scaled back (mcmetal.m tex_convert).
 *
 * <p>With a shader pack the pack's own tone mapping decides the picture: the present only clamps (mode 0), the pack looks as
 * in SDR. The setting applies at once: the targets are made again (the main target's resize) and the layer reconfigured.
 * -Dcadence.hdr=false: never.
 */
public final class Hdr {
	private static final boolean ALLOWED = !"false".equals(System.getProperty("cadence.hdr"));
	/** -Dcadence.hdr.gain: how much brighter than SDR white the brightest emissive texel is meant to be (linear), default 4. */
	private static final float GAIN = Float.parseFloat(System.getProperty("cadence.hdr.gain", "4"));
	private static volatile boolean wanted;
	/** The targets made while this is true are float ones; the layer follows it. Render thread. */
	private static boolean active;
	private static float now = 1, potential = 1;
	private static long readAt;
	private static long scratch;
	private static final List<Runnable> rebuilt = new ArrayList<>();

	private Hdr() {
	}

	/** The setting (saved by the shaders module's config); applied on the render thread's next frame. */
	public static void setWanted(boolean on) {
		wanted = on && ALLOWED;
	}

	public static boolean wanted() {
		return wanted;
	}

	/** Whether the frame is HDR now (float targets, EDR layer). */
	public static boolean active() {
		return active;
	}

	/** The game screen's headroom now and at most (1.0: SDR only), from the last read. */
	public static float headroom() {
		return now;
	}

	public static float potentialHeadroom() {
		return potential;
	}

	/** Linear multiple of SDR white for a fully emissive texel (Cadence's terrain renderer; 1 without HDR). */
	public static float emissiveGain() {
		return active ? GAIN : 1f;
	}

	/** Called after the targets were made again for a change of HDR (WorldScale drops its own target and its scaler). */
	public static void onRebuild(Runnable r) {
		rebuilt.add(r);
	}

	/** Set while the game's frame targets make their textures (HdrFrameTargetMixin, HdrMainTargetMixin). Render thread. */
	public static boolean creatingFrame;

	/** MetalDevice.createTexture: the game's frame colour (the main target's, Cadence's scaled world's) is made as RGBA16Float. */
	static boolean floatTarget() {
		return active && creatingFrame;
	}

	/** MetalSurface.configure: the layer's HDR bit. */
	static boolean layerHdr() {
		return active;
	}

	/**
	 * Render thread, every presented frame (MetalSurface.blitFromTexture): a change of the setting applied (targets, layer), the
	 * screen's headroom read every quarter second, and the present's tone mapping set from it.
	 */
	static void frame(MetalSurface surface) {
		if (wanted != active) {
			active = wanted;
			rebuildTargets();
			surface.reconfigure();
			System.out.println("[cadence] HDR " + (active ? "on" : "off") + " (headroom " + now + ", at most " + potential + ")");
		}
		// (each guarded: this runs inside every present, so an exception here would end the game, BH7-01)
		guarded(0, dev.cadence.metal.own.OwnEmission::prepare);
		guarded(1, dev.cadence.metal.neural.SmoothTextures::frame);
		guarded(2, dev.cadence.metal.audio.WorldAcoustics::frame);
		if (!active) return;
		long t = System.nanoTime();
		if (t - readAt > 250_000_000L) {
			readAt = t;
			if (scratch == 0) scratch = MemoryUtil.nmemAlloc(8);
			Native.screenHeadroom(scratch);
			int a = MemoryUtil.memGetInt(scratch), b = MemoryUtil.memGetInt(scratch + 4);
			if (a > 0) now = a / 1000f;
			if (b > 0) potential = b / 1000f;
		}
		boolean pack = dev.cadence.metal.own.OwnTerrain.packActive();
		// (the knee: everything up to SDR white unchanged once there is real headroom; with a little, the roll-off starts lower so
		// highlights still read as brighter than white)
		// (the target: the headroom now once macOS gives some; before that, what the screen can reach. macOS raises the headroom only
		// while a layer shows values above 1.0, so mapping to "now" = 1.0 would never ask for it; until it arrives, highlights clip)
		float h = Math.max(1f, now > 1.05f ? now : potential);
		float knee = h >= 1.5f ? 1f : Math.max(0.75f, h - 0.5f);
		Native.hdrTone(h, knee, pack || h < 1.05f ? 0 : 1);
	}

	private static final boolean[] hookFailed = new boolean[3];

	/** A between-frames hook run; the first exception logged and that hook not run again. */
	private static void guarded(int i, Runnable hook) {
		if (hookFailed[i]) return;
		try {
			hook.run();
		} catch (Throwable t) {
			hookFailed[i] = true;
			System.out.println("[cadence] " + (i == 0 ? "HDR emission" : i == 1 ? "Smooth Textures" : "World Reverb") + " stopped: " + t);
		}
	}

	private static void rebuildTargets() {
		try {
			var mc = net.minecraft.client.Minecraft.getInstance();
			var main = mc.gameRenderer == null ? null : mc.gameRenderer.mainRenderTarget();
			if (main != null) main.resize(main.width, main.height);
		} catch (Throwable t) {
			System.out.println("[cadence] HDR: the main target couldn't be made again: " + t);
		}
		for (Runnable r : rebuilt) {
			try {
				r.run();
			} catch (Throwable t) {
				System.out.println("[cadence] HDR: " + t);
			}
		}
	}

	/**
	 * Self-test (hdrstats): the main target read back as it is (half floats, no conversion) once the GPU is done: its largest
	 * channel value and the share of pixels above 1.0 (HDR highlights), to {@code out}. Render thread.
	 */
	public static void debugStats(java.util.function.Consumer<String> out) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
		if (mc.gameRenderer == null || device == null
			|| !(((com.mojang.renderpearl.frontend.FrontendCommandEncoder) device.createCommandEncoder()).backend() instanceof MetalEncoder encoder)) {
			out.accept("no encoder");
			return;
		}
		var main = mc.gameRenderer.mainRenderTarget();
		if (!(main.getColorTexture() instanceof MetalTexture tex)) {
			out.accept("no main target");
			return;
		}
		int w = tex.getWidth(0), h = tex.getHeight(0);
		if (!active) {
			out.accept("hdr off (" + w + "x" + h + " RGBA8)");
			return;
		}
		long bytes = (long) w * h * 8;
		long buffer = Native.bufferNew(encoder.ctx, bytes);
		long address = Native.bufferContents(buffer);
		Native.blitTextureToBuffer(encoder.enc, tex.handle, 0, 0, 0, w, h, buffer, 0, w * 8);
		encoder.afterGpuFinishes(() -> {
			float max = 0;
			long above = 0, huge = 0, at = 0;
			StringBuilder hugeAt = new StringBuilder();
			for (long i = 0; i < (long) w * h; i++) {
				float m = 0;
				for (int c = 0; c < 4; c++) {
					float v = Float.float16ToFloat(MemoryUtil.memGetShort(address + i * 8 + c * 2));
					if (c < 3) m = Math.max(m, v);
					else if (v > 16f || !Float.isFinite(v)) m = Math.max(m, 0);  // (alpha reported apart below)
				}
				if (m > max) {
					max = m;
					at = i;
				}
				if (m > 1.001f) above++;
				if (m > 16f || !Float.isFinite(m)) {
					if (huge++ < 4) hugeAt.append(' ').append(i % w).append(',').append(i / w);
				}
			}
			long a = address + at * 8;
			String px = String.format(java.util.Locale.ROOT, "(%.2f %.2f %.2f a %.2f) at %d,%d", Float.float16ToFloat(MemoryUtil.memGetShort(a)),
				Float.float16ToFloat(MemoryUtil.memGetShort(a + 2)), Float.float16ToFloat(MemoryUtil.memGetShort(a + 4)), Float.float16ToFloat(MemoryUtil.memGetShort(a + 6)),
				at % w, at / w);
			Native.release(buffer);
			out.accept(String.format(java.util.Locale.ROOT, "%dx%d max %.2f %s, above 1.0: %.3f%% of pixels, above 16: %d%s, headroom %.2f (at most %.2f), gain %.1f",
				w, h, max, px, 100.0 * above / ((double) w * h), huge, hugeAt, now, potential, emissiveGain()));
		});
	}

	/** Debug (-Dcadence.hdr.debug): a texture's level 0 read back once the GPU is done, its non-zero bytes counted. */
	public static void debugNonZero(com.mojang.renderpearl.api.textures.GpuTexture texture, int bytesPerPixel, String what) {
		var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
		if (!(((com.mojang.renderpearl.frontend.FrontendCommandEncoder) device.createCommandEncoder()).backend() instanceof MetalEncoder encoder)) return;
		MetalTexture tex = (MetalTexture) texture;
		int w = tex.getWidth(0), h = tex.getHeight(0);
		long buffer = Native.bufferNew(encoder.ctx, (long) w * h * bytesPerPixel);
		long address = Native.bufferContents(buffer);
		Native.blitTextureToBuffer(encoder.enc, tex.handle, 0, 0, 0, w, h, buffer, 0, w * bytesPerPixel);
		encoder.afterGpuFinishes(() -> {
			long nz = 0;
			for (long i = 0; i < (long) w * h * bytesPerPixel; i++) if (MemoryUtil.memGetByte(address + i) != 0) nz++;
			Native.release(buffer);
			System.out.println("[cadence] HDR debug: " + what + " " + w + "x" + h + " non-zero bytes " + nz);
		});
	}
}
