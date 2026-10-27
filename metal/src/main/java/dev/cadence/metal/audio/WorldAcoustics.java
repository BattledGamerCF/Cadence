package dev.cadence.metal.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * World Reverb (Options > Music & Sounds > World Reverb, with the Apple sound engine): the space around you heard in the game's
 * sounds. Four times a second 26 rays go out from the listener through the blocks (up to 24 blocks): how many hit something and how
 * far tells a field from a forest, a room, a tunnel or a great cave, and sets Apple's environment reverb (its room preset, its
 * level, how much of each sound goes in). Ten times a second each playing sound gets the blocks between you and it as occlusion:
 * a mob behind a wall is muffled, one in the open isn't. Measured on the render thread from the game's own blocks; the sound
 * thread's voices are only touched through AppleAudio (they're checked to still exist).
 */
public final class WorldAcoustics {
	private static volatile boolean wanted = !"false".equals(System.getProperty("cadence.audio.reverb"));
	private static long roomAt, soundsAt;
	private static boolean applied;
	private static float level = -40f, blend;
	private static int preset = -1, candidate = -1, candidateCount;
	private static final float[][] DIRS = directions();
	private static long ids, pos;
	private static final int MAX_SOUNDS = 64;
	/** The last estimate, for the self-test: hits of 26, mean distance, preset, level, sounds occluded. */
	private static volatile String last = "";

	private WorldAcoustics() {
	}

	public static void setWanted(boolean on) {
		wanted = on;
	}

	public static boolean wanted() {
		return wanted;
	}

	public static String last() {
		return last;
	}

	/** Render thread, every frame (MetalSurface's present): the room and the sounds' occlusion, each on its own clock. */
	public static void frame() {
		if (!AppleAudio.running() || !wanted) {
			if (applied) {
				AppleAudio.environment(false, 0, -40f, 0f);
				applied = false;
			}
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Level lv = mc.level;
		if (lv == null || mc.gameRenderer == null) return;
		Vec3 eye = mc.gameRenderer.mainCamera().position();
		long now = System.nanoTime();
		if (now - roomAt > 250_000_000L) {
			roomAt = now;
			room(lv, eye);
		}
		if (now - soundsAt > 100_000_000L) {
			soundsAt = now;
			sounds(lv, eye);
		}
	}

	/** The space around: its reverb. */
	private static void room(Level lv, Vec3 eye) {
		int hits = 0, up = 0, upOpen = 0;
		float sum = 0;
		for (float[] d : DIRS) {
			float t = march(lv, eye, d[0], d[1], d[2], 24f, null);
			if (d[1] > 0.1f) {
				up++;
				if (t < 0) upOpen++;
			}
			if (t >= 0) {
				hits++;
				sum += t;
			}
		}
		// (the ground below hits everywhere: open sky above is what says "outdoors")
		float sky = up > 0 ? upOpen / (float) up : 0;
		float enclosure = hits / (float) DIRS.length, mean = hits > 0 ? sum / hits : 24f;
		boolean underwater = lv.getFluidState(BlockPos.containing(eye)).isSource() || !lv.getFluidState(BlockPos.containing(eye)).isEmpty();
		// AVAudioUnitReverbPreset: 0 small room, 1 medium room, 2 large room, 3 medium hall, 4 large hall, 5 plate, 8 cathedral
		int p = underwater ? 5 : mean < 3 ? 0 : mean < 6 ? 1 : mean < 10 ? 2 : mean < 15 ? 3 : enclosure > 0.85f ? 8 : 4;
		// outdoors (few hits): little reverb; enclosed: more, and more of each sound in it
		boolean outdoors = sky > 0.6f;
		float targetLevel = outdoors ? -30f + 8f * enclosure : -16f + 14f * enclosure, targetBlend = outdoors ? 0.05f + 0.1f * enclosure : 0.2f + 0.4f * enclosure;
		if (underwater) {
			targetLevel = -4f;
			targetBlend = 0.6f;
		}
		// (eased: walking into a cave fills in over a second or so)
		level += (targetLevel - level) * 0.35f;
		blend += (targetBlend - blend) * 0.35f;
		// (a new preset only once it has been measured twice in a row: loading one resets the reverb, so a space on the edge
		// between two doesn't flip it 4 times a second)
		if (p != preset) {
			candidateCount = p == candidate ? candidateCount + 1 : 1;
			candidate = p;
			if (candidateCount >= 2 || preset < 0) preset = p;
		}
		AppleAudio.environment(true, preset, level, blend);
		applied = true;
		last = String.format(java.util.Locale.ROOT, "hits %d/%d mean %.1f sky %.2f preset %d level %.1f dB blend %.2f%s%s", hits, DIRS.length, mean, sky, preset,
			level, blend, outdoors ? " outdoors" : "", underwater ? " underwater" : "");
	}

	/** Each playing sound: the blocks between it and the listener as occlusion (a full block about 9 dB, glass, leaves and the like less). */
	private static void sounds(Level lv, Vec3 eye) {
		if (ids == 0) {
			ids = MemoryUtil.nmemAlloc(MAX_SOUNDS * 8L);
			pos = MemoryUtil.nmemAlloc(MAX_SOUNDS * 12L);
		}
		int n = AppleAudio.snapshot(ids, pos, MAX_SOUNDS);
		for (int i = 0; i < n; i++) {
			float sx = MemoryUtil.memGetFloat(pos + i * 12L), sy = MemoryUtil.memGetFloat(pos + i * 12L + 4), sz = MemoryUtil.memGetFloat(pos + i * 12L + 8);
			double dx = sx - eye.x, dy = sy - eye.y, dz = sz - eye.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			float db = 0;
			if (d > 1.0 && d < 48) {
				float[] blocks = {0};
				march(lv, eye, (float) (dx / d), (float) (dy / d), (float) (dz / d), (float) d - 0.5f, blocks);
				db = Math.max(-40f, -9f * blocks[0]);
			}
			AppleAudio.occlusion(MemoryUtil.memGetAddress(ids + i * 8L), db);
		}
	}

	/**
	 * Steps through the blocks from o along d (unit) up to max: with count == null, the distance to the first block that stops
	 * sound (-1 if none); with count, all of them weighed into count[0] (and -1).
	 */
	private static float march(Level lv, Vec3 o, float dx, float dy, float dz, float max, float[] count) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int x = (int) Math.floor(o.x), y = (int) Math.floor(o.y), z = (int) Math.floor(o.z);
		int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
		double tx = dx != 0 ? ((dx > 0 ? x + 1 - o.x : o.x - x) / Math.abs(dx)) : Double.MAX_VALUE;
		double ty = dy != 0 ? ((dy > 0 ? y + 1 - o.y : o.y - y) / Math.abs(dy)) : Double.MAX_VALUE;
		double tz = dz != 0 ? ((dz > 0 ? z + 1 - o.z : o.z - z) / Math.abs(dz)) : Double.MAX_VALUE;
		double ddx = dx != 0 ? 1 / Math.abs(dx) : Double.MAX_VALUE, ddy = dy != 0 ? 1 / Math.abs(dy) : Double.MAX_VALUE, ddz = dz != 0 ? 1 / Math.abs(dz) : Double.MAX_VALUE;
		double t = 0;
		while (t < max) {
			if (tx < ty && tx < tz) { x += sx; t = tx; tx += ddx; }
			else if (ty < tz) { y += sy; t = ty; ty += ddy; }
			else { z += sz; t = tz; tz += ddz; }
			if (t >= max) break;
			if (!lv.isLoaded(p.set(x, y, z))) return -1;
			BlockState s = lv.getBlockState(p);
			if (s.isAir()) continue;
			float w = s.isSolidRender() ? 1f : s.getBlock() instanceof LeavesBlock ? 0.3f : !s.getCollisionShape(lv, p).isEmpty() ? 0.5f : 0f;
			if (w <= 0) continue;
			if (count == null) return (float) t;
			count[0] += w;
		}
		return -1;
	}

	/** 26 directions: the cube's faces, edges and corners. */
	private static float[][] directions() {
		float[][] out = new float[26][];
		int n = 0;
		for (int x = -1; x <= 1; x++)
			for (int y = -1; y <= 1; y++)
				for (int z = -1; z <= 1; z++) {
					if (x == 0 && y == 0 && z == 0) continue;
					float l = (float) Math.sqrt(x * x + y * y + z * z);
					out[n++] = new float[] {x / l, y / l, z / l};
				}
		return out;
	}
}
