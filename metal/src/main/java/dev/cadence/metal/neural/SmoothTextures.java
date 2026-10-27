package dev.cadence.metal.neural;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cadence.metal.MetalBridge;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.system.MemoryUtil;

/**
 * Smooth Textures (Video Settings > Cadence: Apple Hardware): the block textures at twice their resolution, upscaled once by an
 * ML model on the Neural Engine (VideoToolbox's low-latency super resolution, mcneural.m mcsm_upscale): the pixel art drawn with
 * smooth, sharp detail instead of square pixels. Cadence's terrain renderer draws with the 2x atlas (its levels below are the
 * game's own); animated sprites (water, lava, fire, portals) keep the game's frames, copied in each frame. Made again when the
 * atlas is (a resource pack change). 4x the atlas's memory (about 64 MB more for the default atlas). Off by default.
 */
public final class SmoothTextures {
	private static volatile boolean wanted;
	private static volatile String status = "";
	private static GpuTexture tex2x, forAtlas;
	private static GpuTextureView view2x;
	/** The animated sprites: x, y, w, h each (1x pixels), refreshed every frame; their largest size. */
	private static int[] anim = new int[0];
	private static int animMaxW, animMaxH;
	private static long animBuf;
	// the work between the readback and the upload
	private static volatile int state;  // 0 idle, 1 reading back, 2 upscaling, 3 ready to upload
	private static long src, out;
	private static int w, h, mips;
	private static GpuTexture working;

	private SmoothTextures() {
	}

	public static void setWanted(boolean on) {
		wanted = on;
		status = "";
	}

	public static boolean wanted() {
		return wanted;
	}

	public static String status() {
		return status;
	}

	/** Own terrain's draws (OwnTerrain.drawOpaque/drawTranslucent): the 2x atlas in place of the game's, once it's ready. */
	public static GpuTextureView substitute(GpuTextureView atlas) {
		return wanted && view2x != null && atlas != null && atlas.texture() == forAtlas ? view2x : atlas;
	}

	/** Render thread, between frames (Hdr.frame: outside any pass): the work's steps, and the animated sprites' refresh. */
	public static void frame() {
		if (!wanted) {
			if (view2x != null) release();
			return;
		}
		if (!(Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS) instanceof TextureAtlas atlas)) return;
		GpuTexture t = atlas.getTexture();
		if (t == null) return;
		var encoder = MetalBridge.encoder(((com.mojang.renderpearl.frontend.FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
		if (state == 0 && t != forAtlas && t != working) {
			start(atlas, t, encoder);
			return;
		}
		if (state == 3 && t == working) {
			upload(t, encoder);
			return;
		}
		if (view2x != null && t == forAtlas && anim.length > 0)
			NeuralNative.smoothAnim(MetalBridge.ctx(encoder), MetalBridge.enc(encoder), MetalBridge.textureHandle(t), MetalBridge.textureHandle(tex2x), animBuf,
				anim.length / 4, animMaxW, animMaxH);
	}

	private static void start(TextureAtlas atlas, GpuTexture t, Object encoder) {
		working = t;
		w = t.getWidth(0);
		h = t.getHeight(0);
		mips = t.getMipLevels();
		List<int[]> still = new ArrayList<>(), moving = new ArrayList<>();
		for (TextureAtlasSprite s : ((dev.cadence.metal.mixin.AtlasDoubleAtlasAccess) atlas).cadence$adSprites()) {
			int x0 = Math.round(s.getU0() * w), x1 = Math.round(s.getU1() * w), y0 = Math.round(s.getV0() * h), y1 = Math.round(s.getV1() * h);
			if (x1 <= x0 || y1 <= y0) continue;
			(s.isAnimated() ? moving : still).add(new int[] {x0, y0, x1 - x0, y1 - y0});
		}
		state = 1;
		status = "Preparing";
		long bytes = (long) w * h * 4;
		long buffer = MetalBridge.newBuffer(MetalBridge.ctx(encoder), bytes);
		long address = MetalBridge.bufferContents(buffer);
		MetalBridge.readTexture(MetalBridge.enc(encoder), MetalBridge.textureHandle(t), w, h, 4, buffer);
		MetalBridge.afterGpu(encoder, () -> {
			src = MemoryUtil.nmemAlloc(bytes);
			MemoryUtil.memCopy(address, src, bytes);
			MetalBridge.releaseObject(buffer);
			state = 2;
			status = "Upscaling";
			Thread th = new Thread(() -> upscale(still), "cadence-smooth-textures");
			th.setDaemon(true);
			th.start();
		});
		int[] a = new int[moving.size() * 4];
		int mw = 1, mh = 1;
		for (int i = 0; i < moving.size(); i++) {
			System.arraycopy(moving.get(i), 0, a, i * 4, 4);
			mw = Math.max(mw, moving.get(i)[2]);
			mh = Math.max(mh, moving.get(i)[3]);
		}
		anim = a;
		animMaxW = mw;
		animMaxH = mh;
	}

	private static void upscale(List<int[]> still) {
		long t0 = System.nanoTime();
		out = MemoryUtil.nmemAlloc((long) w * h * 16);
		long rects = MemoryUtil.nmemAlloc(Math.max(1, still.size()) * 16L), err = MemoryUtil.nmemCalloc(1, 512);
		try {
			for (int i = 0; i < still.size(); i++)
				for (int k = 0; k < 4; k++) MemoryUtil.memPutInt(rects + (i * 4L + k) * 4, still.get(i)[k]);
			int n = NeuralNative.smoothUpscale(src, w, h, out, rects, still.size(), err, 512);
			if (n < 0) {
				status = "Can't: " + MemoryUtil.memUTF8(err);
				System.out.println("[cadence] smooth textures: " + status);
				MemoryUtil.nmemFree(out);
				out = 0;
				state = 0;
				forAtlas = working;  // (not tried again for this atlas)
				working = null;
				return;
			}
			System.out.printf(java.util.Locale.ROOT, "[cadence] smooth textures: %d sprites upscaled 2x on the Neural Engine in %.0f ms (%d animated kept)%n", n,
				(System.nanoTime() - t0) / 1e6, anim.length / 4);
			state = 3;
		} finally {
			MemoryUtil.nmemFree(rects);
			MemoryUtil.nmemFree(err);
			MemoryUtil.nmemFree(src);
			src = 0;
		}
	}

	private static void upload(GpuTexture t, Object encoder) {
		var device = RenderSystem.getDevice();
		release();
		try (var writable = dev.cadence.metal.MetalStorage.writableTextures()) {
			tex2x = device.createTexture(() -> "Cadence smooth block atlas", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
				GpuFormat.RGBA8_UNORM, w * 2, h * 2, 1, mips + 1);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		device.createCommandEncoder().writeToTexture(tex2x, MemoryUtil.memByteBuffer(out, w * h * 16), 0, 0, 0, 0, w * 2, h * 2);
		MemoryUtil.nmemFree(out);
		out = 0;
		NeuralNative.smoothCopyLevels(MetalBridge.enc(encoder), MetalBridge.textureHandle(t), MetalBridge.textureHandle(tex2x));
		view2x = device.createTextureView(tex2x);
		if (animBuf != 0) MemoryUtil.nmemFree(animBuf);
		animBuf = MemoryUtil.nmemAlloc(Math.max(16, anim.length * 4L));
		for (int i = 0; i < anim.length; i++) MemoryUtil.memPutInt(animBuf + i * 4L, anim[i]);
		forAtlas = t;
		working = null;
		state = 0;
		status = "";
	}

	private static void release() {
		if (view2x != null) view2x.close();
		if (tex2x != null) tex2x.close();
		view2x = null;
		tex2x = null;
		forAtlas = null;
	}
}
