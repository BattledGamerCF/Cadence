// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import com.mojang.blaze3d.platform.Monitor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/**
 * -Dcadence.edgeCap=true|N (off by default; mixins applied only with the flag, EdgeMixinPlugin): while the integrated server is generating new terrain near the player, cap the frame
 * rate at 2x the display's refresh rate (or at N). At uncapped ~1000+ fps on a 5K laptop the GPU's power and heat take the
 * CPU budget the world generator needs, and in fast flight over new terrain the edge of the render distance stays empty
 * (measured on the test Macs). The signal is any generation step of a chunk (ChunkStatusTasks.generateStructureStarts ... generateFeatures,
 * never run for chunks loaded from disk) within RD + 32 chunks of the player in the last second: generation reaches out past
 * the render distance, so the cap is on before the edge goes missing. Singleplayer (and LAN host) only: without an
 * integrated server no chunk is generated in this JVM and the limit is never touched. Without the property: no effect.
 */
public final class EdgeCap {
	private static final String MODE = System.getProperty("cadence.edgeCap", "");
	public static final boolean ON = !MODE.isEmpty() && !"false".equals(MODE);
	private static final int FIXED = ON && !"true".equals(MODE) ? Integer.parseInt(MODE) : 0;
	private static final long HOLD_NS = Long.getLong("cadence.edgeCap.holdMs", 1000) * 1_000_000L;
	private static final int MARGIN = Integer.getInteger("cadence.edgeCap.margin", 32);
	private static final boolean LOG = Boolean.getBoolean("cadence.edgeCap.log");
	private static volatile long lastGenNs = Long.MIN_VALUE / 2;
	/** The player's chunk and the reach (RD + margin), published by the render thread for the worldgen threads; reach 0 = none. */
	private static volatile long player;
	private static volatile int reach;
	private static final java.util.concurrent.atomic.AtomicInteger GEN = new java.util.concurrent.atomic.AtomicInteger(),
		NEAR = new java.util.concurrent.atomic.AtomicInteger(), MIN_D = new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE),
		MAX_D = new java.util.concurrent.atomic.AtomicInteger();
	private static long nextDiag;
	private static boolean capped;
	private static long since, nextLog;
	private static double cappedS;
	private static int cap;

	private EdgeCap() {}

	/** Worldgen threads: a brand-new chunk starts generating. */
	public static void generating(ChunkPos pos) {
		int r = reach;
		if (r == 0) return;
		long p = player;
		int dx = pos.x() - (int) (p >> 32), dz = pos.z() - (int) p;
		int d2 = dx * dx + dz * dz;
		if (LOG) {
			GEN.incrementAndGet();
			int d = (int) Math.sqrt(d2);
			MIN_D.accumulateAndGet(d, Math::min);
			MAX_D.accumulateAndGet(d, Math::max);
		}
		if (d2 <= r * r) {
			lastGenNs = System.nanoTime();
			if (LOG) NEAR.incrementAndGet();
		}
	}

	/** Render thread, once a frame (FramerateLimitTracker.getFramerateLimit). */
	public static int limit(int limit) {
		if (!ON) return limit;
		Minecraft mc = Minecraft.getInstance();
		boolean c = false;
		if (mc.hasSingleplayerServer() && mc.player != null) {
			long now = System.nanoTime();
			player = (long) SectionPos.blockToSectionCoord(mc.player.getBlockX()) << 32 | (SectionPos.blockToSectionCoord(mc.player.getBlockZ()) & 0xffffffffL);
			reach = mc.options.getEffectiveRenderDistance() + MARGIN;
			c = now - lastGenNs < HOLD_NS;
			if (LOG && now >= nextDiag) {
				nextDiag = now + 2_000_000_000L;
				System.out.printf("cadence-edgecap: generated %d chunks (%d within reach %d), distance %d..%d, capped %b%n", GEN.getAndSet(0),
					NEAR.getAndSet(0), reach, MIN_D.getAndSet(Integer.MAX_VALUE), MAX_D.getAndSet(0), capped);
			}
			if (c != capped) {
				if (c) {
					since = now;
					cap = FIXED > 0 ? FIXED : 2 * refresh(mc);
				} else {
					cappedS += (now - since) / 1e9;
				}
				capped = c;
				if (now >= nextLog || !c) {
					System.out.printf("cadence-edgecap: %s at %d fps (capped %.1f s so far)%n", c ? "cap on" : "cap off", cap, cappedS);
					nextLog = now + 1_000_000_000L;
				}
			}
		} else {
			// no integrated server (multiplayer, menus): never capped, and the worldgen hook records nothing
			reach = 0;
			capped = false;
		}
		return capped ? Math.min(limit, cap) : limit;
	}

	private static int refresh(Minecraft mc) {
		Monitor m = mc.getWindow().findBestMonitor();
		float hz = m == null ? 0 : m.currentMode().getRefreshRate();
		return hz >= 30 ? Math.round(hz) : 120;
	}
}
