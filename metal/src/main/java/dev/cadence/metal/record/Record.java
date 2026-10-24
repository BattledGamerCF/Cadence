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
 * client's loaded world ray-marched from a voxel copy (RecordWorld), each block face its texture's average colour; entities as boxes; the F3
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
	private static final int ENTITY_MAX = 32, ENTITY_BYTES = 48, TEXT_LINES = 11, FRAME_BYTES = 4 * 64 + 12 * 16;  // record.metal Frame

	private static long rec, ctx, encObj, entities, entitiesAddr, frameBuf;
	private static RecordHud hud;
	private static final int[] HUD_STATE = new int[RecordHud.STATE];
	private static Object encoder;
	private static RecordWorld world;
	private static boolean failed, detached;
	private static int failures;
	private static float viewYaw, viewPitch;
	private static final net.minecraft.core.BlockPos.MutableBlockPos BACK = new net.minecraft.core.BlockPos.MutableBlockPos();
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
		inBucket = 0;
		camsKnown = 0;
		failures = 0;
		if (on && rec != 0) failed = false;  // (a failure in a frame: tried again; one in init, no Metal, stays)
		MetalBridge.recordPresent(on);
		// off: the world copy goes (block and light changes aren't followed while off; on again, it's made anew)
		if (!on) worldClosed();
	}

	/** Whether this frame is a record frame: chosen, in a world, nothing on screen over it. */
	public static boolean active() {
		if (!chosen || failed) return false;
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null && mc.gui.screen() == null && mc.gui.overlay() == null;
	}

	/**
	 * The texture this frame presents, 0: the game's own (a frame that wasn't a record frame, a screen open: its handle is
	 * taken once). Every other present shows the in-between frame instead (frame interpolation's frames are shown too).
	 */
	public static long presentHandle() {
		long h = presentHandle;
		presentHandle = 0;
		if (h == 0 || !chosen) return 0;
		if (interpolate && camsKnown >= 3 && (presents++ & 1) != 0) {
			long mid = RecordNative.mid(rec);
			if (mid != 0) return mid;
		}
		return h;
	}

	private static long presents;

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
		// (nothing keeps the level: its entities, its chunks)
		worldLevel = null;
		java.util.Arrays.fill(NEAR, null);
		chosenCount = -1;
		camsKnown = 0;
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
						img.setPixel(x, h - 1 - y, 0xFF000000 | (c & 0xFF) << 16 | (c & 0xFF00) | (c >> 16) & 0xFF);  // (rows stored bottom first)
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

	/**
	 * (testing) The last frame's inputs as files in dir, for tools/record_bench.m: frame.bin (the Frame struct), grid,
	 * palette, occ, light, entities, text (the shared buffers as they are now). Render thread. Whether written.
	 */
	public static boolean dump(java.nio.file.Path dir) {
		RecordWorld wd = world;
		if (rec == 0 || wd == null) return false;
		try {
			java.nio.file.Files.createDirectories(dir);
			long cells = (long) wd.w * wd.w * wd.h, bricks = cells / 512;
			write(dir.resolve("frame.bin"), frameBuf, FRAME_BYTES);
			write(dir.resolve("grid.bin"), RecordNative.contents(wd.grid), cells * 2);
			write(dir.resolve("palette.bin"), RecordNative.contents(wd.palette), (long) RecordWorld.PALETTE_MAX * RecordWorld.PALETTE_STRIDE);
			write(dir.resolve("occ.bin"), RecordNative.contents(wd.occ), bricks * 64);
			write(dir.resolve("light.bin"), RecordNative.contents(wd.light), cells);
			write(dir.resolve("entities.bin"), entitiesAddr, ENTITY_REGION + (long) TILES_MAX * 4);
			write(dir.resolve("hud.bin"), hud.address(), hud.bytes());
			return true;
		} catch (java.io.IOException e) {
			System.out.println("[cadence] record dump failed: " + e);
			return false;
		}
	}

	private static void write(java.nio.file.Path file, long address, long length) throws java.io.IOException {
		try (var ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
			java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
			var buf = MemoryUtil.memByteBuffer(address, (int) length);
			while (buf.hasRemaining()) ch.write(buf);
		}
	}

	// ---- the frame ----

	/** GameRenderer.render's turn (the game's own drawing skipped): this frame, compute only. False: not drawn here. */
	public static boolean frame() {
		long t0 = System.nanoTime();
		if (lastEnd != 0) outsideNanos += t0 - lastEnd;
		try {
			return frame0();
		} finally {
			lastEnd = System.nanoTime();
			insideNanos += lastEnd - t0;
			timedFrames++;
		}
	}

	// (the frame's CPU time, in Record.frame and in the rest of the game's loop between two of them; read by the self-test)
	private static long lastEnd, insideNanos, outsideNanos, timedFrames;

	/** "cpu 12.3 us in the record frame + 20.1 us in the game loop" since the last call; resets. */
	public static String cpuSplit() {
		long n = Math.max(1, timedFrames);
		String r = String.format(java.util.Locale.ROOT, "cpu %.1f us in the record frame + %.1f us in the game loop", insideNanos / 1000.0 / n,
			outsideNanos / 1000.0 / n);
		insideNanos = outsideNanos = timedFrames = 0;
		return r;
	}

	private static boolean frame0() {
		Minecraft mc = Minecraft.getInstance();
		try {
			if (!init(mc)) return false;
			ClientLevel level = mc.level;
			if (world == null || world.minY != level.getMinY() || world.h != level.getHeight() || worldLevel != level) {
				if (world != null) world.release();
				world = null;
				world = new RecordWorld(ctx, level, WLOG);
				worldLevel = level;
			}
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
			// the eye: the camera entity's, or in third person (F5) up to 4 blocks behind it (in front, turned round, for the
			// front view), short of the first solid block, as vanilla's camera (the game's own camera isn't updated while its
			// world drawing is skipped)
			Vec3 eye = cam.getEyePosition(partial);
			viewYaw = cam.getViewYRot(partial);
			viewPitch = cam.getViewXRot(partial);
			var type = mc.options.getCameraType();
			detached = !type.isFirstPerson();
			if (detached) {
				if (type.isMirrored()) {
					viewYaw += 180;
					viewPitch = -viewPitch;
				}
				double ry = Math.toRadians(viewYaw), rp = Math.toRadians(viewPitch);
				double fx = -Math.sin(ry) * Math.cos(rp), fy = -Math.sin(rp), fz = Math.cos(ry) * Math.cos(rp);
				double back = 0;
				while (back < 4 && mc.level.getBlockState(BACK.set(eye.x - fx * (back + 0.25), eye.y - fy * (back + 0.25), eye.z - fz * (back + 0.25))).isAir())
					back += 0.25;
				eye = new Vec3(eye.x - fx * back, eye.y - fy * back, eye.z - fz * back);
			}
			// (a gap since the last record frame, a screen or the mode off: the in-between frame's history starts again)
			if (lastEnd != 0 && System.nanoTime() - lastEnd > 50_000_000L) camsKnown = 0;
			world.tintAt(eye.x, eye.z);
			world.follow(eye.x, eye.z);
			// window upkeep: a little of it at most frames (it's done in a few seconds after joining or moving far)
			if ((frames & 15) == 0) world.work(400_000);
			int[] size = size(mc);
			if (size[0] != w || size[1] != h) {
				w = size[0];
				h = size[1];
				RecordNative.size(rec, ctx, w, h);
			}
			writeFrame(mc, cam, eye, partial);
			long hudBuffer = hud.frame(frames, w, h, Math.max(1, h / 240), lines, 7, CHAT_SHOWN, chatLines, HUD_STATE);
			long tex = RecordNative.frame(rec, encObj, frameBuf, FRAME_BYTES, world.grid, world.palette, entities, hudBuffer, world.occ, world.light,
				interpolate ? 1 : 0);
			if (tex == 0) return false;
			MetalBridge.recordFrame();
			presentHandle = tex;
			count();
			return true;
		} catch (RuntimeException | LinkageError e) {
			// tried again with a new world copy; the third failure stops the mode until it's turned on again
			presentHandle = 0;
			if (world != null) world.release();
			world = null;
			failed = ++failures >= 3;
			System.out.println("[cadence] record frame failed" + (failed ? ", record mode stopped: " : ": ") + e);
			e.printStackTrace(System.out);
			return false;
		}
	}

	private static ClientLevel worldLevel;
	private static long frames;

	private static final int[] SIZE = new int[2];

	private static int[] size(Minecraft mc) {
		var win = mc.getWindow();
		double aspect = Math.max(0.5, (double) win.getWidth() / Math.max(1, win.getHeight()));
		int hh = Math.clamp(height, 90, 1080);
		SIZE[0] = Math.clamp((int) Math.round(hh * aspect), 160, 2048) & ~1;
		SIZE[1] = hh;
		return SIZE;
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
		entities = RecordNative.buffer(ctx, ENTITY_REGION + (long) TILES_MAX * 4);
		entitiesAddr = RecordNative.contents(entities);
		MemoryUtil.memSet(entitiesAddr, 0, ENTITY_REGION + (long) TILES_MAX * 4);
		hud = new RecordHud(ctx);
		frameBuf = MemoryUtil.nmemCalloc(1, FRAME_BYTES);
		loadFont(mc);
		return true;
	}

	private static void loadFont(Minecraft mc) {
		var id = net.minecraft.resources.Identifier.withDefaultNamespace("textures/font/ascii.png");
		try (InputStream in = mc.getResourceManager().getResourceOrThrow(id).open();
			var img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
			int iw = img.getWidth(), ih = img.getHeight();
			// (its alpha, scaled to 128 x 128: the HUD draws 8 x 8 glyph cells)
			byte[] alpha = new byte[128 * 128];
			for (int y = 0; y < 128; y++)
				for (int x = 0; x < 128; x++) alpha[y * 128 + x] = (byte) (img.getPixel(x * iw / 128, y * ih / 128) >>> 24);
			hud.font(alpha);
		} catch (Exception e) {
			throw new IllegalStateException("record mode: no font: " + e);
		}
	}


	// ---- the frame's parameters (record.metal Frame) ----

	/** The last two frames' cameras (world coordinates): a the newer. */
	private static final double[] camA = new double[5], camB = new double[5];
	private static int camsKnown;

	private static void writeFrame(Minecraft mc, Entity cam, Vec3 eye, float partial) {
		RecordWorld wd = world;
		float yaw = viewYaw, pitch = viewPitch;
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
		// (the in-between frame's two cameras the same: no reprojection needed)
		boolean still = java.util.Arrays.equals(camA, camB);
		System.arraycopy(camA, 0, camB, 0, 5);
		set(camA, eye, yaw, pitch);
		camsKnown = Math.min(3, camsKnown + 1);
		// sky and fog: a plain day sky, darkened with the time of day as the blocks' sky light is
		darken = mc.level.getSkyDarken();
		gamma = mc.options.gamma().get().floatValue();
		float day = Math.max(0.06f, 1 - darken / 12f);
		var dim = mc.level.dimensionType();
		if (dim.hasSkyLight()) {
			putRgb(f + 256, 0x78A7FF, day);
			putRgb(f + 272, 0xC0D8FF, day);
		} else if (dim.hasCeiling()) {  // (the Nether: a dark red haze)
			putRgb(f + 256, 0x330808, 1);
			putRgb(f + 272, 0x330808, 1);
		} else {  // (the End: dark violet)
			putRgb(f + 256, 0x150F1E, 1);
			putRgb(f + 272, 0x150F1E, 1);
		}
		float fogEnd = (wd.w >> 1) - 8;  // (the window reaches at least 24 blocks each way: RecordWorld.follow)
		MemoryUtil.memPutFloat(f + 284, fogEnd);
		int mask = wd.w - 1;
		MemoryUtil.memPutInt(f + 288, wd.wlog);
		MemoryUtil.memPutInt(f + 292, wd.h);
		MemoryUtil.memPutInt(f + 296, wd.ox & mask);
		MemoryUtil.memPutInt(f + 300, wd.oz & mask);
		int count = writeEntities(mc, cam, partial, f);
		int lines = writeText(mc, eye, yaw, pitch);
		MemoryUtil.memPutInt(f + 304, w);
		MemoryUtil.memPutInt(f + 308, h);
		MemoryUtil.memPutInt(f + 312, count);
		MemoryUtil.memPutInt(f + 316, lines);
		MemoryUtil.memPutFloat(f + 320, 0);  // (unused: was the atlas's mip count)
		MemoryUtil.memPutFloat(f + 324, Math.max(1, h / 240));
		MemoryUtil.memPutFloat(f + 328, fogEnd * 0.6f);
		MemoryUtil.memPutFloat(f + 332, Math.max(1, wd.top));
		MemoryUtil.memPutInt(f + 336, interpolate && camsKnown >= 3 ? still ? 2 : 1 : 0);
		MemoryUtil.memPutInt(f + 340, darken);
		MemoryUtil.memPutFloat(f + 344, gamma);
		writeHud(mc, f + 352);
		// the block the crosshair is on, and how far it's broken
		int tw = 0;
		if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			var bp = bh.getBlockPos();
			MemoryUtil.memPutInt(f + 400, bp.getX() - wd.ox);
			MemoryUtil.memPutInt(f + 404, bp.getY() - wd.minY);
			MemoryUtil.memPutInt(f + 408, bp.getZ() - wd.oz);
			tw = 2 + (mc.gameMode != null && mc.gameMode.isDestroying() ? mc.gameMode.getDestroyStage() : -1);
		}
		MemoryUtil.memPutInt(f + 412, tw);
		// air, hurt, the fluid the eye is in, XP; the latest chat lines
		var p = mc.player;
		int air = p.getAirSupply(), maxAir = Math.max(1, p.getMaxAirSupply());
		var eyePos = net.minecraft.core.BlockPos.containing(eye);
		var fluid = mc.level.getFluidState(eyePos);
		int in = 0;
		if (!fluid.isEmpty() && eye.y < eyePos.getY() + fluid.getHeight(mc.level, eyePos))
			in = fluid.is(net.minecraft.tags.FluidTags.WATER) ? 1 : fluid.is(net.minecraft.tags.FluidTags.LAVA) ? 2 : 0;
		HUD_STATE[12] = air < maxAir || in == 1 ? Math.clamp(air * 20 / maxAir, 0, 20) : 255;
		HUD_STATE[13] = Math.clamp((int) (p.experienceProgress * 1000), 0, 1000);
		MemoryUtil.memPutInt(f + 416, HUD_STATE[12]);
		MemoryUtil.memPutInt(f + 420, Math.clamp(p.hurtTime, 0, 10));
		MemoryUtil.memPutInt(f + 424, in);
		MemoryUtil.memPutInt(f + 428, Math.clamp((int) (p.experienceProgress * 1000), 0, 1000));
		MemoryUtil.memPutInt(f + 432, chatLines);
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
		for (int k = 0; k < 9; k++) HUD_STATE[k] = itemColor(inv.getItem(k));
		HUD_STATE[9] = inv.getSelectedSlot();
		HUD_STATE[10] = Math.clamp((int) Math.ceil(p.getHealth()), 0, 20);
		HUD_STATE[11] = Math.clamp(p.getFoodData().getFoodLevel(), 0, 20);
		for (int k = 0; k < 12; k++) MemoryUtil.memPutInt(at + k * 4L, HUD_STATE[k]);
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

	private static void putRgb(long at, int rgb, float k) {
		MemoryUtil.memPutFloat(at, ((rgb >> 16) & 255) / 255f * k);
		MemoryUtil.memPutFloat(at + 4, ((rgb >> 8) & 255) / 255f * k);
		MemoryUtil.memPutFloat(at + 8, (rgb & 255) / 255f * k);
	}

	private static int darken;
	private static float gamma = 0.5f;

	/** record.metal brightness(): a light level (sky << 4 | block) as vanilla's lightmap makes it brightness. */
	static float brightness(int sky, int block) {
		return curve(Math.max(Math.max(sky - darken, 0), block));
	}

	/** A light level 0..15 as brightness: vanilla's lightmap curve (no ambient light), the Brightness option, its floor. */
	private static float curve(int level) {
		float lv = level / 15f;
		float b = lv / (4 - 3 * lv), nb = 1 - b;
		b += (1 - nb * nb * nb * nb - b) * gamma;
		return b * 0.96f + 0.04f;
	}

	/** An rgb colour times k. */
	private static int scaled(int rgb, float k) {
		int r = Math.min(255, (int) (((rgb >> 16) & 255) * k)), g = Math.min(255, (int) (((rgb >> 8) & 255) * k)), b = Math.min(255, (int) ((rgb & 255) * k));
		return r << 16 | g << 8 | b;
	}

	private static final Entity[] NEAR = new Entity[ENTITY_MAX];
	private static final double[] NEAR_D = new double[ENTITY_MAX];
	private static final int[] NEAR_LIT = new int[ENTITY_MAX];  // their colours, lit where they stand

	/** The nearest entities within the window as boxes, each with the screen rectangle it can cover. Their count. */
	private static int writeEntities(Minecraft mc, Entity cam, float partial, long f) {
		RecordWorld wd = world;
		// (the nearest entities chosen again every 16th frame: the walk over every entity was a tenth of the frame; their
		// positions still follow every frame)
		if ((frames & 15) != 0 && chosenCount >= 0) return placeEntities(cam, partial, f, chosenCount);
		int n = 0;
		double half = wd.w / 2.0 - 10;  // (inside the window, which reaches 24 blocks or more each way)
		Vec3 c = cam.position();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == cam && !detached) continue;  // (in first person the eye is in the camera entity's box)
			if (e.isInvisible() || e instanceof net.minecraft.world.entity.Interaction || e instanceof net.minecraft.world.entity.Marker) continue;
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
		for (int i = 0; i < n; i++) {
			var at = NEAR[i].blockPosition();
			NEAR_LIT[i] = scaled(colorOf(NEAR[i]), brightness(mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, at),
				mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at)));
		}
		chosenCount = n;
		return placeEntities(cam, partial, f, n);
	}

	private static int chosenCount = -1;

	/**
	 * Behind the entity boxes in their buffer (mcrecord.m binds it at ENTITY_REGION): per 8 x 8 pixel tile of the frame a mask
	 * of the boxes whose rectangle touches it, so a pixel tests only those (a test per box per pixel was ~20% of a frame).
	 */
	private static final int ENTITY_REGION = ENTITY_MAX * ENTITY_BYTES, TILES_MAX = (2048 / 8) * (1152 / 8);
	// The masks as written (WRITTEN) and this frame's (NEXT), and the tiles with a bit in either: a tile's word is written only
	// when it changes, never zeroed first (the GPU reads the buffer while the CPU writes it: a zeroed tile dropped its boxes).
	private static final int[] WRITTEN = new int[TILES_MAX], NEXT = new int[TILES_MAX];
	private static final int[] TILES_SET = new int[TILES_MAX], TILES_NEXT = new int[TILES_MAX];
	private static int tilesSet, tilesNext;

	private static int placeEntities(Entity cam, float partial, long f, int n) {
		RecordWorld wd = world;
		long tiles = entitiesAddr + ENTITY_REGION;
		tilesNext = 0;
		int tilesX = (w + 7) >> 3;
		MemoryUtil.memPutInt(f + 436, tilesX);
		int out = 0;
		for (int i = 0; i < n; i++) {
			Entity e = NEAR[i];
			if (e == null || e.isRemoved()) continue;
			// (the box where the entity is this frame, between its last two ticks: no Vec3/AABB made, 32 a frame at 20k frames)
			AABB b = e.getBoundingBox();
			double mx = (e.xo - e.getX()) * (1 - partial) - wd.ox, my = (e.yo - e.getY()) * (1 - partial) - wd.minY,
				mz = (e.zo - e.getZ()) * (1 - partial) - wd.oz;
			double x0 = b.minX + mx, y0 = b.minY + my, z0 = b.minZ + mz, x1 = b.maxX + mx, y1 = b.maxY + my, z1 = b.maxZ + mz;
			int[] rect = screenRect(f, x0, y0, z0, x1, y1, z1);
			if (rect == null) continue;
			long at = entitiesAddr + (long) out * ENTITY_BYTES;
			MemoryUtil.memPutFloat(at, (float) x0);
			MemoryUtil.memPutFloat(at + 4, (float) y0);
			MemoryUtil.memPutFloat(at + 8, (float) z0);
			MemoryUtil.memPutInt(at + 12, NEAR_LIT[i]);
			MemoryUtil.memPutFloat(at + 16, (float) x1);
			MemoryUtil.memPutFloat(at + 20, (float) y1);
			MemoryUtil.memPutFloat(at + 24, (float) z1);
			for (int k = 0; k < 4; k++) MemoryUtil.memPutInt(at + 32 + k * 4L, rect[k]);
			for (int ty = rect[1] >> 3; ty <= rect[3] >> 3; ty++)
				for (int tx = rect[0] >> 3; tx <= rect[2] >> 3; tx++) {
					int idx = ty * tilesX + tx;
					if (NEXT[idx] == 0) TILES_NEXT[tilesNext++] = idx;
					NEXT[idx] |= 1 << out;
				}
			out++;
		}
		// tiles that had boxes and have none now, then the new masks where they changed
		for (int k = 0; k < tilesSet; k++) {
			int idx = TILES_SET[k];
			if (NEXT[idx] == 0 && WRITTEN[idx] != 0) {
				MemoryUtil.memPutInt(tiles + idx * 4L, 0);
				WRITTEN[idx] = 0;
			}
		}
		for (int k = 0; k < tilesNext; k++) {
			int idx = TILES_NEXT[k];
			if (NEXT[idx] != WRITTEN[idx]) {
				MemoryUtil.memPutInt(tiles + idx * 4L, NEXT[idx]);
				WRITTEN[idx] = NEXT[idx];
			}
			NEXT[idx] = 0;
			TILES_SET[k] = idx;
		}
		tilesSet = tilesNext;
		return out;
	}

	private static final double[] CX = new double[8], CY = new double[8], CZ = new double[8];  // (screenRect's corners; render thread)

	/**
	 * The pixels a box can cover (the camera at f), null when off screen. A box partly behind the eye is clipped at the near
	 * plane (its edges' crossings projected with its corners in front), so only a box the eye is inside covers the whole
	 * screen: every pixel in a box's rectangle tests it, a test per pixel for a whole-screen one.
	 */
	private static int[] screenRect(long f, double x0, double y0, double z0, double x1, double y1, double z1) {
		float px = MemoryUtil.memGetFloat(f), py = MemoryUtil.memGetFloat(f + 4), pz = MemoryUtil.memGetFloat(f + 8);
		if (px >= x0 && px <= x1 && py >= y0 && py <= y1 && pz >= z0 && pz <= z1) return rect(0, 0, w - 1, h - 1);
		float fx = MemoryUtil.memGetFloat(f + 16), fy = MemoryUtil.memGetFloat(f + 20), fz = MemoryUtil.memGetFloat(f + 24), tx = MemoryUtil.memGetFloat(f + 28);
		float rx = MemoryUtil.memGetFloat(f + 32), rz = MemoryUtil.memGetFloat(f + 40), ty = MemoryUtil.memGetFloat(f + 44);
		float ux = MemoryUtil.memGetFloat(f + 48), uy = MemoryUtil.memGetFloat(f + 52), uz = MemoryUtil.memGetFloat(f + 56);
		// the corners in camera space (right, up, forward)
		double[] cx = CX, cy = CY, cz = CZ;
		for (int k = 0; k < 8; k++) {
			double vx = ((k & 1) == 0 ? x0 : x1) - px, vy = ((k & 2) == 0 ? y0 : y1) - py, vz = ((k & 4) == 0 ? z0 : z1) - pz;
			cx[k] = vx * rx + vz * rz;
			cy[k] = vx * ux + vy * uy + vz * uz;
			cz[k] = vx * fx + vy * fy + vz * fz;
		}
		final double near = 0.05;
		double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
		boolean any = false;
		for (int k = 0; k < 8; k++) {
			if (cz[k] >= near) {
				double sx = cx[k] / (cz[k] * tx), sy = cy[k] / (cz[k] * ty);
				minX = Math.min(minX, sx);
				maxX = Math.max(maxX, sx);
				minY = Math.min(minY, sy);
				maxY = Math.max(maxY, sy);
				any = true;
			}
			// the edges to the corners one bit up: where one crosses the near plane
			for (int bit = 1; bit < 8; bit <<= 1) {
				if ((k & bit) != 0) continue;
				int j = k | bit;
				if ((cz[k] < near) == (cz[j] < near)) continue;
				double s = (near - cz[k]) / (cz[j] - cz[k]);
				double sx = (cx[k] + (cx[j] - cx[k]) * s) / (near * tx), sy = (cy[k] + (cy[j] - cy[k]) * s) / (near * ty);
				minX = Math.min(minX, sx);
				maxX = Math.max(maxX, sx);
				minY = Math.min(minY, sy);
				maxY = Math.max(maxY, sy);
			}
		}
		if (!any) return null;  // (wholly behind the eye)
		if (maxX < -1 || minX > 1 || maxY < -1 || minY > 1) return null;
		minX = Math.max(-1.5, minX);
		maxX = Math.min(1.5, maxX);
		minY = Math.max(-1.5, minY);
		maxY = Math.min(1.5, maxY);
		int a = (int) Math.floor((minX * 0.5 + 0.5) * w) - 1, b = (int) Math.floor((0.5 - maxY * 0.5) * h) - 1;
		int c = (int) Math.ceil((maxX * 0.5 + 0.5) * w) + 1, d = (int) Math.ceil((0.5 - minY * 0.5) * h) + 1;
		return rect(Math.max(0, a), Math.max(0, b), Math.min(w - 1, c), Math.min(h - 1, d));
	}

	private static final int[] RECT = new int[4];  // (screenRect's result, used at once; render thread)

	private static int[] rect(int a, int b, int c, int d) {
		RECT[0] = a;
		RECT[1] = b;
		RECT[2] = c;
		RECT[3] = d;
		return RECT;
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
			inBucket = 0;
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

	private static final int CHAT_MAX = TEXT_LINES - 8;
	private static final String[] CHAT = new String[CHAT_MAX];
	private static final long[] CHAT_AT = new long[CHAT_MAX];
	private static int chatLines;
	private static final String[] CHAT_SHOWN = new String[CHAT_MAX];
	/** How long a chat line shows (vanilla's 10 s; -Dcadence.record.chatSeconds, for tests). */
	private static final long CHAT_NANOS = Long.getLong("cadence.record.chatSeconds", 10) * 1_000_000_000L;

	/** A chat message arrived (ChatComponent.addMessage): shown bottom left for 10 seconds, the newest last. Render thread. */
	public static void chat(String message) {
		if (!chosen) return;
		for (String part : message.split("\n")) {
			System.arraycopy(CHAT, 1, CHAT, 0, CHAT_MAX - 1);
			System.arraycopy(CHAT_AT, 1, CHAT_AT, 0, CHAT_MAX - 1);
			CHAT[CHAT_MAX - 1] = part;
			CHAT_AT[CHAT_MAX - 1] = System.nanoTime();
		}
		textAt = 0;
	}

	private static int writeText(Minecraft mc, Vec3 eye, float yaw, float pitch) {
		long now = System.nanoTime();
		if (now - textAt < BUCKET && lines[0] != null) return 7;
		textAt = now;
		String fps = rendered == 0 ? "measuring..." : interpolate ? shown() + " fps (" + rendered + " rendered)" : rendered + " fps";
		lines[0] = "Minecraft 26.3 Cadence Record (Metal " + (MetalBridge.metal4() ? "4" : "3") + ")";
		lines[1] = fps;
		lines[2] = String.format(java.util.Locale.ROOT, "XYZ: %.3f / %.5f / %.3f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
		lines[3] = String.format(java.util.Locale.ROOT, "Facing: %s (%.1f / %.1f)", mc.player.getDirection().getSerializedName(), net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot()), mc.player.getXRot());
		double gpu = RecordNative.gpuMicros();
		if (gpu > 0) gpuUs = gpu;
		lines[4] = String.format(java.util.Locale.ROOT, "%dx%d, %d chunks, GPU %.0f us/frame", w, h, world.w / 32, gpuUs);
		lines[5] = "Best: " + best + " fps" + (interpolate ? " (incl. interpolated)" : "");
		lines[6] = interpolate ? "Interpolated frames counted apart" : "Every frame rendered";
		// the chat lines still showing (10 s), oldest first
		chatLines = 0;
		for (int i = 0; i < CHAT_MAX; i++)
			if (CHAT[i] != null && now - CHAT_AT[i] < CHAT_NANOS) CHAT_SHOWN[chatLines++] = CHAT[i];
		return 7;
	}
}
