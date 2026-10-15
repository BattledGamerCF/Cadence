// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.util.Arrays;
import dev.cadence.metal.MetalBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * Black-box rebuild latency (-Dcadence.own.latency=true): the same measurement whatever renders the terrain (vanilla, Sodium,
 * ours). Every period the integrated server toggles one block on the camera's centre ray (gold block / air); the time the
 * client's level first shows the change is t0, and the first frame whose centre pixel changes is when the rebuilt section
 * reached the screen. The pixel is read back with a 1 x 1 blit at the end of each level frame into a ring of 4 slots,
 * read 3 frames later (never in flight then). Latencies are logged every 5 s (frames encoded after t0 until the pixel
 * changed, and milliseconds). Starts when the camera has held still for 2 s (the bench's settle hold).
 */
public final class OwnLatency {
	public static final boolean ON = Boolean.getBoolean("cadence.own.latency");
	private static final long PERIOD_NS = Long.getLong("cadence.own.latency.periodMs", 700) * 1_000_000L;
	private static final double DISTANCE = Double.parseDouble(System.getProperty("cadence.own.latency.distance", "6"));
	private static final int SLOTS = 4, THRESHOLD = 24;

	private static long buffer, address;
	private static final long[] slotTime = new long[SLOTS];
	private static final long[] slotFrame = new long[SLOTS];
	private static long frame;
	private static BlockPos pos;
	private static Vec3 lastCam;
	private static long stillSince, lastToggle, t0, t0Frame;
	private static boolean gold, waitingClient, waitingPixel;
	private static int before = -1;
	private static final double[] ms = new double[4096];
	private static final long[] frames = new long[4096];
	private static int count, misses;
	private static long logAt;

	private OwnLatency() {
	}

	/** Render thread, after the level is rendered into the main target. */
	public static void afterLevel(net.minecraft.client.renderer.state.level.CameraRenderState camera) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
		if (encoder == null) return;
		long now = System.nanoTime();
		frame++;
		if (buffer == 0) {
			buffer = MetalBridge.newBuffer(MetalBridge.ctx(encoder), SLOTS * 16);
			address = MetalBridge.bufferContents(buffer);
		}
		// the frame from 3 frames ago: its pixel is in memory now
		int old = (int) ((frame - 3) % SLOTS);
		if (frame > 3) check(MemoryUtil.memGetInt(address + old * 16L), slotTime[old], slotFrame[old]);
		GpuTexture color = mc.gameRenderer.mainRenderTarget().getColorTexture();
		int slot = (int) (frame % SLOTS);
		MetalBridge.readTextureRegion(MetalBridge.enc(encoder), MetalBridge.textureHandle(color), color.getWidth(0) / 2, color.getHeight(0) / 2, 1, 1, 4, buffer,
			slot * 16L);
		slotTime[slot] = now;
		slotFrame[slot] = frame;

		Vec3 cam = camera.pos;
		if (lastCam == null || cam.distanceToSqr(lastCam) > 1e-6) {
			stillSince = now;
			lastCam = cam;
			pos = null;
			waitingClient = waitingPixel = false;
			return;
		}
		if (now - stillSince < 2_000_000_000L) return;
		if (pos == null) {
			// vanilla's view vector from the camera's rotation (Entity.calculateViewVector)
			double xr = Math.toRadians(camera.xRot), yr = Math.toRadians(-camera.yRot);
			Vec3 dir = new Vec3(Math.sin(yr) * Math.cos(xr), -Math.sin(xr), Math.cos(yr) * Math.cos(xr));
			pos = BlockPos.containing(cam.add(dir.scale(DISTANCE)));
			gold = false;
			lastToggle = 0;
			System.out.println("cadence-own latency: toggling " + pos + " (" + mc.level.getBlockState(pos) + ")");
		}
		BlockState want = gold ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
		if (waitingClient && mc.level.getBlockState(pos).equals(want)) {
			waitingClient = false;
			waitingPixel = true;
			t0 = now;
			t0Frame = frame;
		}
		if (!waitingClient && !waitingPixel && now - lastToggle >= PERIOD_NS) {
			gold = !gold;
			BlockState next = gold ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
			MinecraftServer server = mc.getSingleplayerServer();
			if (server == null) return;
			BlockPos p = pos;
			server.execute(() -> server.overworld().setBlock(p, next, 3));
			lastToggle = now;
			waitingClient = true;
			before = -1;
		}
		if (now - logAt > 5_000_000_000L) {
			logAt = now;
			log();
		}
	}

	private static void check(int rgba, long time, long fr) {
		if (!waitingPixel) {
			before = rgba;
			return;
		}
		if (fr < t0Frame) {
			before = rgba;  // a frame encoded before the client had the change: the baseline
			return;
		}
		if (before == -1) return;
		int d = 0;
		for (int c = 0; c < 24; c += 8) d = Math.max(d, Math.abs((rgba >>> c & 255) - (before >>> c & 255)));
		if (d > THRESHOLD) {
			if (count < ms.length) {
				ms[count] = (time - t0) / 1e6;
				frames[count] = fr - t0Frame;
				count++;
			}
			waitingPixel = false;
		} else if (time - t0 > 1_000_000_000L) {
			misses++;
			waitingPixel = false;
		}
	}

	private static void log() {
		if (count == 0) {
			System.out.println("cadence-own latency: no samples yet (misses " + misses + ")");
			return;
		}
		double[] s = Arrays.copyOf(ms, count);
		Arrays.sort(s);
		double sum = 0;
		long fsum = 0;
		for (int i = 0; i < count; i++) {
			sum += ms[i];
			fsum += frames[i];
		}
		System.out.printf("cadence-own latency: n %d, mean %.2f ms, p50 %.2f, p90 %.2f, max %.2f ms, mean %.1f frames, misses %d%n", count, sum / count,
			s[count / 2], s[Math.min(count - 1, (int) (count * 0.9))], s[count - 1], (double) fsum / count, misses);
	}
}
