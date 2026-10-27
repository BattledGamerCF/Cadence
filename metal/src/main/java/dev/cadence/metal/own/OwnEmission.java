package dev.cadence.metal.own;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.cadence.metal.Hdr;
import dev.cadence.metal.MetalBridge;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

/**
 * HDR (dev.cadence.metal.Hdr): the block atlas's emission mask, an R8 texture the atlas's size that own_fs (terrain.metal) reads
 * at the same coordinates. A sprite counts as emissive when blocks that give light use it and no block that doesn't: the
 * lit furnace's front and the jack o'lantern's face, not the furnace's sides or the pumpkin's. Its texels hold the block's light
 * level / 15 (the brightest user's); the shader weighs that by each texel's own brightness. Lava (drawn by the fluid renderer,
 * not a block model) is added by name. Made again when the atlas is (a resource reload makes a new one). Render thread.
 */
public final class OwnEmission {
	private static GpuTexture mask, forAtlas;
	private static boolean bound, failed;
	private static final boolean DEBUG = Boolean.getBoolean("cadence.hdr.debug");

	private OwnEmission() {
	}

	/**
	 * Between frames (Hdr.frame, outside any render pass: the upload is a blit): the mask made for the block atlas in use, and
	 * the gain and mask handed to mcown.m while HDR is on (gain 1 otherwise).
	 */
	public static void prepare() {
		if (!Hdr.active() || failed) {
			if (bound) {
				OwnNative.hdr(1f, 0);
				bound = false;
			}
			return;
		}
		if (!(Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS) instanceof TextureAtlas atlas)) return;
		GpuTexture t = atlas.getTexture();
		if (t == null) return;
		if (t != forAtlas || mask == null) {
			try {
				build(atlas, t);
			} catch (Throwable e) {
				failed = true;
				System.out.println("[cadence] HDR: no emission mask for the block atlas: " + e);
				return;
			}
		}
		OwnNative.hdr(Hdr.emissiveGain(), MetalBridge.textureHandle(mask));
		bound = true;
	}

	private static void build(TextureAtlas atlas, GpuTexture atlasTexture) {
		Minecraft mc = Minecraft.getInstance();
		long t0 = System.nanoTime();
		Map<TextureAtlasSprite, Integer> emit = new IdentityHashMap<>();
		Map<TextureAtlasSprite, Boolean> plain = new IdentityHashMap<>();
		var models = mc.getModelManager().getBlockStateModelSet();
		RandomSource random = RandomSource.create(42L);
		List<BlockStateModelPart> parts = new ArrayList<>();
		Direction[] sides = Direction.values();
		for (Block block : BuiltInRegistries.BLOCK) {
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				int e = state.getLightEmission();
				parts.clear();
				models.get(state).collectParts(random, parts);
				for (BlockStateModelPart part : parts) {
					for (int d = 0; d <= sides.length; d++) {
						for (var quad : part.getQuads(d < sides.length ? sides[d] : null)) {
							TextureAtlasSprite sprite = quad.materialInfo().sprite();
							if (e > 0) emit.merge(sprite, e, Math::max);
							else plain.put(sprite, Boolean.TRUE);
						}
					}
				}
			}
		}
		for (String lava : new String[] {"block/lava_still", "block/lava_flow"}) {
			TextureAtlasSprite s = atlas.getSprite(Identifier.withDefaultNamespace(lava));
			if (s != null && s != atlas.missingSprite()) emit.put(s, 15);
		}
		int w = atlasTexture.getWidth(0), h = atlasTexture.getHeight(0);
		ByteBuffer pixels = MemoryUtil.memCalloc(w * h);
		int marked = 0;
		try {
			for (var entry : emit.entrySet()) {
				TextureAtlasSprite s = entry.getKey();
				if (plain.containsKey(s) || s == atlas.missingSprite()) continue;
				byte v = (byte) Math.min(255, entry.getValue() * 17);
				// (the sprite's own UV rectangle: getX/getY include the mip padding around it)
				int x0 = Math.round(s.getU0() * w), x1 = Math.round(s.getU1() * w), y0 = Math.round(s.getV0() * h), y1 = Math.round(s.getV1() * h);
				int sw = x1 - x0, sh = y1 - y0;
				for (int y = Math.max(0, y0); y < Math.min(h, y1); y++)
					for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) pixels.put(y * w + x, v);
				marked++;
				if (DEBUG && marked <= 12) System.out.println("[cadence] HDR emissive " + s.contents().name() + " light " + entry.getValue() + " at " + x0 + "," + y0 + " " + sw + "x" + sh);
			}
			if (DEBUG) {
				int nz = 0;
				for (int i = 0; i < w * h; i++) if (pixels.get(i) != 0) nz++;
				System.out.println("[cadence] HDR mask texels set: " + nz);
			}
			var device = RenderSystem.getDevice();
			if (mask != null) mask.close();
			mask = device.createTexture(() -> "Cadence emission mask", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.R8_UNORM, w, h, 1, 1);
			device.createCommandEncoder().writeToTexture(mask, pixels, 0, 0, 0, 0, w, h);
			if (DEBUG) Hdr.debugNonZero(mask, 1, "emission mask");
		} finally {
			MemoryUtil.memFree(pixels);
		}
		forAtlas = atlasTexture;
		System.out.printf(java.util.Locale.ROOT, "[cadence] HDR: emission mask %dx%d, %d emissive sprites (%.0f ms)%n", w, h, marked, (System.nanoTime() - t0) / 1e6);
	}
}
