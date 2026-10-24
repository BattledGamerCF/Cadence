package dev.cadence.metal.record;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.cadence.metal.MetalBridge;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * Cadence Record Mode: the game at the highest frame rate it can render for real, by the FPS-record rules (the world and its
 * entities drawn every frame, at a playable resolution, an honest counter). Every frame is compute only (record.metal): the
 * client's loaded world ray-marched from a voxel copy (RecordWorld), textured from the block atlas; entities as boxes; the F3
 * text from the game's font; no render pass, whose fixed GPU cost (~53 us on an M2) alone would cap the frame rate below
 * the record. With interpolation on, a second dispatch makes the frame halfway between each two (by reprojection) and the
 * counter shows both: frames made, and of them rendered.
 *
 * <p>Active while chosen, in a world, with no screen open: any screen (pause, inventory, chat) draws the game as usual.
 */
public final class Record {
	private static volatile boolean chosen;
	/** Frame interpolation (-Dcadence.record.interpolate=false: off). */
	public static volatile boolean interpolate = !"false".equals(System.getProperty("cadence.record.interpolate"));
	/** The record resolution's height (16:9 width); -Dcadence.record.height. */
	public static volatile int height = Integer.getInteger("cadence.record.height", 180);
	/** The voxel window's width, log2 (6: 64 blocks, 2 chunks each way, the game's smallest render distance). */
	private static final int WLOG = Integer.getInteger("cadence.record.window", 6);
	private static final int ENTITY_MAX = 32, ENTITY_BYTES = 48, TEXT_LINES = 8, FRAME_BYTES = 4 * 64 + 9 * 16;  // record.metal Frame

	private static long rec, ctx, encObj, entities, text, frameBuf;
	private static Object encoder;
	private static RecordWorld world;
	private static boolean failed;
	private static long presentHandle;
	private static int w, h;

	private Record() {
	}

	public static boolean chosen() {
		return chosen;
	}

	public static void set(boolean on) {
		if (on == chosen) return;
		chosen = on;
		peak = 0;
		bucketStart = 0;
		filled = 0;
		MetalBridge.recordPresent(on);
		if (!on) presentHandle = 0;
	}

	/** Whether this frame is a record frame: chosen, in a world, nothing on screen over it. */
	public static boolean active() {
		if (!chosen || failed) return false;
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null && mc.gui.screen() == null && mc.gui.overlay() == null;
	}

	/** The texture the frame presents (the record frame's colour), 0: the game's own. */
	public static long presentHandle() {
		return chosen ? presentHandle : 0;
	}

	/** The game marked a section for rebuilding (LevelExtractor.setSectionDirty). */
	public static void sectionDirty(int sx, int sy, int sz) {
		RecordWorld wd = world;
		if (wd != null) wd.sectionDirty(sx, sy, sz);
	}

	/** The world was left: its copy goes. */
	public static void worldClosed() {
		if (world != null) {
			world.release();
			world = null;
		}
		presentHandle = 0;
	}

	/** (testing) The latest record frame as a PNG at path. Render thread. Whether one was written. */
	public static boolean snapshot(java.nio.file.Path path) {
		if (rec == 0 || w == 0) return false;
		long px = MemoryUtil.nmemAlloc((long) w * h * 4);
		try {
			if (RecordNative.read(rec, ctx, px) == 0) return false;
			try (var img = new com.mojang.blaze3d.platform.NativeImage(w, h, false)) {
				for (int y = 0; y < h; y++)
					for (int x = 0; x < w; x++) {
						int c = MemoryUtil.memGetInt(px + ((long) y * w + x) * 4L);  // RGBA bytes = ABGR as an int; NativeImage takes ARGB
						img.setPixel(x, y, 0xFF000000 | (c & 0xFF) << 16 | (c & 0xFF00) | (c >> 16) & 0xFF);
					}
				img.writeToFile(path);
			}
			return true;
		} catch (Exception e) {
			System.out.println("[cadence] record snapshot failed: " + e);
			return false;
		} finally {
			MemoryUtil.nmemFree(px);
		}
	}

	// ---- the frame ----

	/** GameRenderer.render's turn (the game's own drawing skipped): this frame, compute only. False: not drawn here. */
	public static boolean frame() {
		Minecraft mc = Minecraft.getInstance();
		try {
			if (!init(mc)) return false;
			ClientLevel level = mc.level;
			if (world == null || world.minY != level.getMinY() || world.h != level.getHeight() || worldLevel != level) {
				if (world != null) world.release();
				world = new RecordWorld(ctx, level, WLOG);
				worldLevel = level;
			}
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
			Vec3 eye = cam.getEyePosition(partial);
			world.tintAt(eye.x, eye.z);
			world.follow(eye.x, eye.z);
			// window upkeep: a little of it at most frames (it's done in a few seconds after joining or moving far)
			if ((frames & 15) == 0) world.work(400_000);
			if ((frames & 31) == 0) world.updateField();
			int[] size = size(mc);
			if (size[0] != w || size[1] != h) {
				w = size[0];
				h = size[1];
				RecordNative.size(rec, ctx, w, h);
			}
			writeFrame(mc, cam, eye, partial);
			long atlas = atlasHandle(mc);
			long tex = RecordNative.frame(rec, encObj, frameBuf, FRAME_BYTES, world.grid, world.bricks, world.palette, entities, text, world.occ, atlas,
				interpolate ? 1 : 0);
			if (tex == 0) return false;
			presentHandle = tex;
			count();
			return true;
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			presentHandle = 0;
			System.out.println("[cadence] record mode stopped: " + e);
			e.printStackTrace(System.out);
			return false;
		}
	}

	private static ClientLevel worldLevel;
	private static long frames;

	private static int[] size(Minecraft mc) {
		var win = mc.getWindow();
		double aspect = Math.max(0.5, (double) win.getWidth() / Math.max(1, win.getHeight()));
		int hh = Math.max(90, height);
		return new int[] {Math.max(160, (int) Math.round(hh * aspect)) & ~1, hh};
	}

	private static boolean init(Minecraft mc) {
		if (rec != 0) return true;
		if (failed) return false;
		encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
		if (encoder == null) {
			failed = true;
			System.out.println("[cadence] record mode needs the Metal backend");
			return false;
		}
		ctx = MetalBridge.ctx(encoder);
		encObj = MetalBridge.enc(encoder);
		String src;
		try (InputStream in = Record.class.getResourceAsStream("/cadence/record/record.metal")) {
			src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException("record.metal missing", e);
		}
		long s = MemoryUtil.nmemAlloc(src.length() * 3L + 1);
		long err = MemoryUtil.nmemAlloc(2048);
		try {
			MemoryUtil.memUTF8(src, true, MemoryUtil.memByteBuffer(s, src.length() * 3 + 1));
			rec = RecordNative.create(ctx, s, err, 2048);
			if (rec == 0) {
				failed = true;
				System.out.println("[cadence] record mode: " + MemoryUtil.memUTF8(err));
				return false;
			}
		} finally {
			MemoryUtil.nmemFree(s);
			MemoryUtil.nmemFree(err);
		}
		entities = RecordNative.buffer(ctx, (long) ENTITY_MAX * ENTITY_BYTES);
		text = RecordNative.buffer(ctx, TEXT_LINES * 64);
		frameBuf = MemoryUtil.nmemCalloc(1, FRAME_BYTES);
		loadFont(mc);
		return true;
	}

	private static void loadFont(Minecraft mc) {
		var id = net.minecraft.resources.Identifier.withDefaultNamespace("textures/font/ascii.png");
		try (InputStream in = mc.getResourceManager().getResourceOrThrow(id).open();
			var img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
			int iw = img.getWidth(), ih = img.getHeight();
			long px = MemoryUtil.nmemAlloc((long) iw * ih * 4);
			try {
				// (scaled to 128 x 128: the trace reads 8 x 8 glyph cells)
				for (int y = 0; y < 128; y++)
					for (int x = 0; x < 128; x++) {
						int c = img.getPixel(x * iw / 128, y * ih / 128);  // ABGR
						MemoryUtil.memPutInt(px + ((long) y * 128 + x) * 4, c);
					}
				RecordNative.font(rec, ctx, px, 128, 128);
			} finally {
				MemoryUtil.nmemFree(px);
			}
		} catch (Exception e) {
			throw new IllegalStateException("record mode: no font: " + e);
		}
	}

	private static int atlasMips;

	private static long atlasHandle(Minecraft mc) {
		var atlas = mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
		var tex = atlas.getTexture();
		atlasMips = Math.max(0, tex.getMipLevels() - 1);
		return MetalBridge.textureHandle(tex);
	}

	// ---- the frame's parameters (record.metal Frame) ----

	/** The last two frames' cameras (world coordinates): a the newer. */
	private static final double[] camA = new double[5], camB = new double[5];
	private static int camsKnown;

	private static void writeFrame(Minecraft mc, Entity cam, Vec3 eye, float partial) {
		RecordWorld wd = world;
		float yaw = cam.getViewYRot(partial), pitch = cam.getViewXRot(partial);
		double fov = mc.options.fov().get();
		float tanY = (float) Math.tan(Math.toRadians(fov) / 2), tanX = tanY * w / h;
		long f = frameBuf;
		camera(f, eye.x - wd.ox, eye.y - wd.minY, eye.z - wd.oz, yaw, pitch, tanX, tanY);
		if (camsKnown == 0) {
			set(camA, eye, yaw, pitch);
			set(camB, eye, yaw, pitch);
		}
		camera(f + 64, camA[0] - wd.ox, camA[1] - wd.minY, camA[2] - wd.oz, (float) camA[3], (float) camA[4], tanX, tanY);
		camera(f + 128, camB[0] - wd.ox, camB[1] - wd.minY, camB[2] - wd.oz, (float) camB[3], (float) camB[4], tanX, tanY);
		// (the in-between frame's camera: halfway between b and a, the angle the short way round)
		double dyaw = ((camA[3] - camB[3]) % 360 + 540) % 360 - 180;
		camera(f + 192, (camA[0] + camB[0]) / 2 - wd.ox, (camA[1] + camB[1]) / 2 - wd.minY, (camA[2] + camB[2]) / 2 - wd.oz, (float) (camB[3] + dyaw / 2),
			(float) ((camA[4] + camB[4]) / 2), tanX, tanY);
		System.arraycopy(camA, 0, camB, 0, 5);
		set(camA, eye, yaw, pitch);
		camsKnown = Math.min(3, camsKnown + 1);
		MemoryUtil.memPutInt(f + 336, interpolate && camsKnown >= 3 ? 1 : 0);
		writeHud(mc, f + 352);
		f += 64;  // (the fields below were laid out for three cameras: they follow the fourth)
		// sky and fog (a clear day: Record Mode is for the counter, the sky's own work isn't drawn)
		putRgb(f + 192, 0x78A7FF);
		putRgb(f + 208, 0xC0D8FF);
		float fogEnd = (wd.w >> 1) - 4;
		MemoryUtil.memPutFloat(f + 208 + 12, fogEnd);
		int mask = wd.w - 1;
		MemoryUtil.memPutInt(f + 224, wd.wlog);
		MemoryUtil.memPutInt(f + 228, wd.h);
		MemoryUtil.memPutInt(f + 232, wd.ox & mask);
		MemoryUtil.memPutInt(f + 236, wd.oz & mask);
		int count = writeEntities(mc, cam, partial, frameBuf);
		int lines = writeText(mc, eye, yaw, pitch);
		MemoryUtil.memPutInt(f + 240, w);
		MemoryUtil.memPutInt(f + 244, h);
		MemoryUtil.memPutInt(f + 248, count);
		MemoryUtil.memPutInt(f + 252, lines);
		MemoryUtil.memPutFloat(f + 256, atlasMips);
		MemoryUtil.memPutFloat(f + 260, Math.max(1, h / 240));
		MemoryUtil.memPutFloat(f + 264, fogEnd * 0.6f);
		MemoryUtil.memPutFloat(f + 268, Math.max(1, wd.top));
		frames++;
	}

	/** A camera (record.metal Camera): window-relative eye, forward + tan(half h fov), right + tan(half v fov), up. */
	private static void camera(long at, double x, double y, double z, float yaw, float pitch, float tanX, float tanY) {
		double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
		double fx = -Math.sin(ry) * Math.cos(rp), fy = -Math.sin(rp), fz = Math.cos(ry) * Math.cos(rp);
		// right = forward x world up, normalised; up = right x forward
		double rx = -fz, rz = fx, rl = Math.max(1e-9, Math.sqrt(rx * rx + rz * rz));
		rx /= rl;
		rz /= rl;
		double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
		float[] v = {(float) x, (float) y, (float) z, 0, (float) fx, (float) fy, (float) fz, tanX, (float) rx, 0, (float) rz, tanY, (float) ux, (float) uy, (float) uz, 0};
		for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(at + i * 4L, v[i]);
	}

	/** The HUD's values (record.metal Frame.hud): nine slot colours, the selected slot, health and food. */
	private static void writeHud(Minecraft mc, long at) {
		var p = mc.player;
		var inv = p.getInventory();
		for (int k = 0; k < 9; k++) MemoryUtil.memPutInt(at + k * 4L, itemColor(inv.getItem(k)));
		MemoryUtil.memPutInt(at + 36, inv.getSelectedSlot());
		MemoryUtil.memPutInt(at + 40, Math.clamp((int) Math.ceil(p.getHealth()), 0, 20));
		MemoryUtil.memPutInt(at + 44, Math.clamp(p.getFoodData().getFoodLevel(), 0, 20));
	}

	private static final java.util.IdentityHashMap<net.minecraft.world.item.Item, Integer> ITEM_COLORS = new java.util.IdentityHashMap<>();

	/** An item's colour on the hotbar: a block's average texture colour, else one made from the item's id. 0: no item. */
	private static int itemColor(net.minecraft.world.item.ItemStack stack) {
		if (stack.isEmpty()) return 0;
		return ITEM_COLORS.computeIfAbsent(stack.getItem(), item -> {
			if (item instanceof net.minecraft.world.item.BlockItem b) return dev.cadence.metal.lod.LodColors.look(b.getBlock().defaultBlockState()).top() | 0x010101;
			int hsh = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).hashCode() * 0x9E3779B1;
			return 0x404040 | (hsh & 0xBFBFBF);
		});
	}

	private static void set(double[] c, Vec3 eye, float yaw, float pitch) {
		c[0] = eye.x;
		c[1] = eye.y;
		c[2] = eye.z;
		c[3] = yaw;
		c[4] = pitch;
	}

	private static void putRgb(long at, int rgb) {
		MemoryUtil.memPutFloat(at, ((rgb >> 16) & 255) / 255f);
		MemoryUtil.memPutFloat(at + 4, ((rgb >> 8) & 255) / 255f);
		MemoryUtil.memPutFloat(at + 8, (rgb & 255) / 255f);
	}

	private static final Entity[] NEAR = new Entity[ENTITY_MAX];
	private static final double[] NEAR_D = new double[ENTITY_MAX];

	/** The nearest entities within the window as boxes, each with the screen rectangle it can cover. Their count. */
	private static int writeEntities(Minecraft mc, Entity cam, float partial, long f) {
		RecordWorld wd = world;
		// (the nearest entities chosen again every 16th frame: the walk over every entity was a tenth of the frame; their
		// positions still follow every frame)
		if ((frames & 15) != 0 && chosenCount >= 0) return placeEntities(cam, partial, f, chosenCount);
		int n = 0;
		double half = wd.w / 2.0 - 2;
		Vec3 c = cam.position();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == cam && mc.options.getCameraType().isFirstPerson()) continue;
			if (e.isInvisible()) continue;
			double dx = e.getX() - c.x, dz = e.getZ() - c.z, d = dx * dx + dz * dz;
			if (Math.abs(dx) > half || Math.abs(dz) > half) continue;
			if (n < ENTITY_MAX) {
				NEAR[n] = e;
				NEAR_D[n++] = d;
			} else {
				int worst = 0;
				for (int i = 1; i < n; i++) if (NEAR_D[i] > NEAR_D[worst]) worst = i;
				if (d < NEAR_D[worst]) {
					NEAR[worst] = e;
					NEAR_D[worst] = d;
				}
			}
		}
		for (int i = n; i < ENTITY_MAX; i++) NEAR[i] = null;
		chosenCount = n;
		return placeEntities(cam, partial, f, n);
	}

	private static int chosenCount = -1;

	private static int placeEntities(Entity cam, float partial, long f, int n) {
		RecordWorld wd = world;
		int out = 0;
		for (int i = 0; i < n; i++) {
			Entity e = NEAR[i];
			if (e == null || e.isRemoved()) continue;
			Vec3 p = e.getPosition(partial);
			AABB b = e.getBoundingBox().move(p.x - e.getX(), p.y - e.getY(), p.z - e.getZ());
			double x0 = b.minX - wd.ox, y0 = b.minY - wd.minY, z0 = b.minZ - wd.oz, x1 = b.maxX - wd.ox, y1 = b.maxY - wd.minY, z1 = b.maxZ - wd.oz;
			int[] rect = screenRect(f, x0, y0, z0, x1, y1, z1);
			if (rect == null) continue;
			long at = RecordNative.contents(entities) + (long) out * ENTITY_BYTES;
			MemoryUtil.memPutFloat(at, (float) x0);
			MemoryUtil.memPutFloat(at + 4, (float) y0);
			MemoryUtil.memPutFloat(at + 8, (float) z0);
			MemoryUtil.memPutInt(at + 12, colorOf(e));
			MemoryUtil.memPutFloat(at + 16, (float) x1);
			MemoryUtil.memPutFloat(at + 20, (float) y1);
			MemoryUtil.memPutFloat(at + 24, (float) z1);
			for (int k = 0; k < 4; k++) MemoryUtil.memPutInt(at + 32 + k * 4L, rect[k]);
			out++;
		}
		return out;
	}

	/** The pixels a box can cover (the camera at f), null when off screen; the whole screen when a corner is behind the eye. */
	private static int[] screenRect(long f, double x0, double y0, double z0, double x1, double y1, double z1) {
		float px = MemoryUtil.memGetFloat(f), py = MemoryUtil.memGetFloat(f + 4), pz = MemoryUtil.memGetFloat(f + 8);
		float fx = MemoryUtil.memGetFloat(f + 16), fy = MemoryUtil.memGetFloat(f + 20), fz = MemoryUtil.memGetFloat(f + 24), tx = MemoryUtil.memGetFloat(f + 28);
		float rx = MemoryUtil.memGetFloat(f + 32), rz = MemoryUtil.memGetFloat(f + 40), ty = MemoryUtil.memGetFloat(f + 44);
		float ux = MemoryUtil.memGetFloat(f + 48), uy = MemoryUtil.memGetFloat(f + 52), uz = MemoryUtil.memGetFloat(f + 56);
		double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
		for (int k = 0; k < 8; k++) {
			double vx = ((k & 1) == 0 ? x0 : x1) - px, vy = ((k & 2) == 0 ? y0 : y1) - py, vz = ((k & 4) == 0 ? z0 : z1) - pz;
			double z = vx * fx + vy * fy + vz * fz;
			if (z < 0.05) return new int[] {0, 0, w - 1, h - 1};
			double sx = (vx * rx + vz * rz) / (z * tx), sy = (vx * ux + vy * uy + vz * uz) / (z * ty);
			minX = Math.min(minX, sx);
			maxX = Math.max(maxX, sx);
			minY = Math.min(minY, sy);
			maxY = Math.max(maxY, sy);
		}
		if (maxX < -1 || minX > 1 || maxY < -1 || minY > 1) return null;
		int a = (int) Math.floor((minX * 0.5 + 0.5) * w) - 1, b = (int) Math.floor((0.5 - maxY * 0.5) * h) - 1;
		int c = (int) Math.ceil((maxX * 0.5 + 0.5) * w) + 1, d = (int) Math.ceil((0.5 - minY * 0.5) * h) + 1;
		return new int[] {Math.max(0, a), Math.max(0, b), Math.min(w - 1, c), Math.min(h - 1, d)};
	}

	private static int colorOf(Entity e) {
		if (e instanceof net.minecraft.world.entity.player.Player) return 0x3B6FD0;
		if (e instanceof net.minecraft.world.entity.item.ItemEntity) return 0xE0C060;
		int hsh = e.getType().hashCode() * 0x9E3779B1;
		return 0x404040 | (hsh & 0xBFBFBF);
	}

	// ---- the counter and the text ----

	private static final long BUCKET = 250_000_000L;
	private static final int[] bucketFrames = new int[4];
	private static final long[] bucketStarts = new long[4];
	private static int bucket, filled, inBucket, rendered, peak, best;
	private static long bucketStart;
	private static long textAt;

	private static void count() {
		long now = System.nanoTime();
		if (bucketStart == 0) {
			bucketStart = now;
			return;
		}
		inBucket++;
		if (now - bucketStart < BUCKET) return;
		bucketFrames[bucket] = inBucket;
		bucketStarts[bucket] = bucketStart;
		bucket = (bucket + 1) & 3;
		filled++;
		inBucket = 0;
		bucketStart = now;
		if (filled < 4) return;
		long total = 0;
		for (int k : bucketFrames) total += k;
		rendered = (int) (total * 1_000_000_000L / Math.max(1, now - bucketStarts[bucket]));
		int made = shown();
		if (made > peak) peak = made;
		if (made > best) best = made;
	}

	/** Frames made a second: rendered, and as many again in between with interpolation on. */
	public static int shown() {
		return interpolate ? rendered * 2 : rendered;
	}

	public static int rendered() {
		return rendered;
	}

	public static int best() {
		return best;
	}

	private static final String[] lines = new String[TEXT_LINES];
	private static double gpuUs;

	/** The record frame's GPU time (microseconds, averaged over a sample of frames). */
	public static double gpuMicros() {
		return gpuUs;
	}

	private static int writeText(Minecraft mc, Vec3 eye, float yaw, float pitch) {
		long now = System.nanoTime();
		if (now - textAt < BUCKET && lines[0] != null) return 7;
		textAt = now;
		String fps = rendered == 0 ? "measuring..." : interpolate ? shown() + " fps (" + rendered + " rendered)" : rendered + " fps";
		lines[0] = "Minecraft 26.3 Cadence Record (Metal " + (MetalBridge.metal4() ? "4" : "3") + ")";
		lines[1] = fps;
		lines[2] = String.format(java.util.Locale.ROOT, "XYZ: %.3f / %.5f / %.3f", eye.x, eye.y - mc.player.getEyeHeight(), eye.z);
		lines[3] = String.format(java.util.Locale.ROOT, "Facing: %s (%.1f / %.1f)", mc.player.getDirection().getSerializedName(), yaw, pitch);
		double gpu = RecordNative.gpuMicros();
		if (gpu > 0) gpuUs = gpu;
		lines[4] = String.format(java.util.Locale.ROOT, "%dx%d, %d chunks, GPU %.0f us/frame", w, h, world.w / 32, gpuUs);
		lines[5] = "Best: " + best + " fps" + (interpolate ? " (incl. interpolated)" : "");
		lines[6] = interpolate ? "Interpolated frames counted apart" : "Every frame rendered";
		long t = RecordNative.contents(text);
		for (int i = 0; i < 7; i++) {
			byte[] b = lines[i].getBytes(StandardCharsets.US_ASCII);
			for (int k = 0; k < 64; k++) MemoryUtil.memPutByte(t + i * 64L + k, k < b.length ? b[k] : 0);
		}
		return 7;
	}
}
