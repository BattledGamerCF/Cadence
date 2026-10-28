package dev.cadence.metal.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Sound Around Corners (Options > Music & Sounds, with World Reverb): a sound behind a wall is heard the way it really
 * travels, round the corner, through the open door or down the tunnel, instead of only muffled through the wall. The air around
 * the listener (33 x 25 x 33 blocks) is searched outwards from the listener's block (a breadth-first search through the blocks
 * sound passes: air, plants, water, open doors and gates); a sound's way back to the listener is pulled tight into straight
 * runs between corners. The sound is then placed at the last corner before you (where it seems to come from), as loud as its
 * whole way round allows and a little duller for each corner; the native side (mcaudio.m, mca_route) keeps whichever is louder,
 * that or the sound muffled through the wall.
 */
final class SoundPaths {
	static final int RX = 16, RY = 12, RZ = 16;
	private static final int SX = 2 * RX + 1, SY = 2 * RY + 1, SZ = 2 * RZ + 1, N = SX * SY * SZ;
	/** 1: sound passes the block. */
	private final byte[] open = new byte[N];
	/** Steps from the listener's block (-1: not reached); the step the search came in by (index of DX/DY/DZ). */
	private final short[] dist = new short[N];
	private final byte[] from = new byte[N];
	private final int[] queue = new int[N];
	private static final int[] DX = {1, -1, 0, 0, 0, 0}, DY = {0, 0, 1, -1, 0, 0}, DZ = {0, 0, 0, 0, 1, -1};
	private int ox, oy, oz;  // the grid's lowest corner in the world
	private int lx = Integer.MIN_VALUE, ly, lz;  // the listener's block the search ran from
	private long builtAt;
	private Level builtFor;
	/** The last route found: the apparent position, the whole way's length, the corners on it. */
	float ax, ay, az, length;
	int corners;

	/**
	 * Before the sounds are routed: the grid and the search, again when the listener has moved to another block or each second.
	 * True when it ran.
	 */
	boolean update(Level lv, Vec3 eye) {
		int bx = (int) Math.floor(eye.x), by = (int) Math.floor(eye.y), bz = (int) Math.floor(eye.z);
		long now = System.nanoTime();
		if (lv == builtFor && bx == lx && by == ly && bz == lz && now - builtAt < 1_000_000_000L) return false;
		builtFor = lv;
		builtAt = now;
		lx = bx;
		ly = by;
		lz = bz;
		ox = bx - RX;
		oy = by - RY;
		oz = bz - RZ;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = 0; y < SY; y++)
			for (int z = 0; z < SZ; z++)
				for (int x = 0; x < SX; x++) {
					p.set(ox + x, oy + y, oz + z);
					open[idx(x, y, z)] = (byte) (passes(lv, p) ? 1 : 0);
				}
		search();
		return true;
	}

	/** Whether sound travels through the block freely: anything without a full collision box, open doors, open gates. */
	private static boolean passes(Level lv, BlockPos.MutableBlockPos p) {
		if (!lv.isLoaded(p)) return false;
		BlockState s = lv.getBlockState(p);
		if (s.isAir()) return true;
		if (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof TrapDoorBlock || s.getBlock() instanceof FenceGateBlock)
			return s.hasProperty(BlockStateProperties.OPEN) && s.getValue(BlockStateProperties.OPEN);
		return !s.isCollisionShapeFullBlock(lv, p);
	}

	private void search() {
		java.util.Arrays.fill(dist, (short) -1);
		int start = idx(RX, RY, RZ);
		if (open[start] == 0) return;  // (the listener's head in a block: nothing routed)
		int head = 0, tail = 0;
		dist[start] = 0;
		queue[tail++] = start;
		while (head < tail) {
			int c = queue[head++];
			int x = c % SX, z = (c / SX) % SZ, y = c / (SX * SZ);
			for (int d = 0; d < 6; d++) {
				int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
				if (nx < 0 || ny < 0 || nz < 0 || nx >= SX || ny >= SY || nz >= SZ) continue;
				int n = idx(nx, ny, nz);
				if (open[n] == 0 || dist[n] >= 0) continue;
				dist[n] = (short) (dist[c] + 1);
				from[n] = (byte) d;
				queue[tail++] = n;
			}
		}
	}

	/**
	 * The way from a sound at s to the listener at eye, into ax/ay/az (where it seems to come from), length and corners. False
	 * when the sound is outside the searched air or no way reaches it.
	 */
	boolean route(Vec3 eye, float sx, float sy, float sz) {
		int x = (int) Math.floor(sx) - ox, y = (int) Math.floor(sy) - oy, z = (int) Math.floor(sz) - oz;
		if (x < 0 || y < 0 || z < 0 || x >= SX || y >= SY || z >= SZ) return false;
		int c = idx(x, y, z);
		if (dist[c] < 0) {
			// (a sound made by a block, in the block: from the nearest reached block beside it)
			int best = -1;
			for (int d = 0; d < 6; d++) {
				int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
				if (nx < 0 || ny < 0 || nz < 0 || nx >= SX || ny >= SY || nz >= SZ) continue;
				int n = idx(nx, ny, nz);
				if (dist[n] >= 0 && (best < 0 || dist[n] < dist[best])) best = n;
			}
			if (best < 0) return false;
			c = best;
		}
		// the way back, listener first (cells by their centres; the ends are the real points)
		int steps = dist[c];
		float[] px = new float[steps + 2], py = new float[steps + 2], pz = new float[steps + 2];
		px[0] = (float) eye.x;
		py[0] = (float) eye.y;
		pz[0] = (float) eye.z;
		px[steps + 1] = sx;
		py[steps + 1] = sy;
		pz[steps + 1] = sz;
		for (int i = steps, at = c; i >= 1; i--) {
			int cx = at % SX, cz = (at / SX) % SZ, cy = at / (SX * SZ);
			px[i] = ox + cx + 0.5f;
			py[i] = oy + cy + 0.5f;
			pz[i] = oz + cz + 0.5f;
			int d = from[at];
			at = idx(cx - DX[d], cy - DY[d], cz - DZ[d]);
		}
		// pulled tight: from each corner, straight on to the farthest point of the way still in sight
		int n = steps + 2, at = 0, corner = -1;
		float len = 0;
		corners = 0;
		while (at < n - 1) {
			int far = at + 1;
			for (int j = n - 1; j > at + 1; j--)
				if (clear(px[at], py[at], pz[at], px[j], py[j], pz[j])) {
					far = j;
					break;
				}
			len += dist3(px[at], py[at], pz[at], px[far], py[far], pz[far]);
			if (far < n - 1) {
				corners++;
				if (corner < 0) corner = far;
			}
			at = far;
		}
		length = len;
		// heard from the first corner (straight at the sound if there is none), as far away as the way is long
		int k = corner < 0 ? n - 1 : corner;
		float dx = px[k] - px[0], dy = py[k] - py[0], dz = pz[k] - pz[0], dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dl < 1e-3f) {
			dx = 0;
			dy = 0;
			dz = 1;
			dl = 1;
		}
		ax = px[0] + dx / dl * len;
		ay = py[0] + dy / dl * len;
		az = pz[0] + dz / dl * len;
		return true;
	}

	/** Whether a straight line between two points stays in air the search has (a walk through the grid's blocks). */
	private boolean clear(float x0, float y0, float z0, float x1, float y1, float z1) {
		float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0, l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		int steps = (int) Math.ceil(l * 3);
		int ex = (int) Math.floor(x1), ey = (int) Math.floor(y1), ez = (int) Math.floor(z1);
		for (int i = 1; i < steps; i++) {
			float t = i / (float) steps;
			int wx = (int) Math.floor(x0 + dx * t), wy = (int) Math.floor(y0 + dy * t), wz = (int) Math.floor(z0 + dz * t);
			if (wx == ex && wy == ey && wz == ez) continue;  // (the end's own block: a sound made by a block, in it)
			int x = wx - ox, y = wy - oy, z = wz - oz;
			if (x < 0 || y < 0 || z < 0 || x >= SX || y >= SY || z >= SZ) return false;
			if (open[idx(x, y, z)] == 0) return false;
		}
		return true;
	}

	private static float dist3(float x0, float y0, float z0, float x1, float y1, float z1) {
		float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static int idx(int x, int y, int z) {
		return (y * SZ + z) * SX + x;
	}
}
