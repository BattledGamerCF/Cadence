package dev.cadence.shaders.cadence.memory;

import dev.cadence.metal.backend.GpuMemory;
import dev.cadence.metal.backend.MacSystem;
import dev.cadence.metal.framegen.FrameGen;
import dev.cadence.shaders.runtime.ShaderRuntime;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cadence: memory given back while the player is away. After a minute with no keyboard or mouse input in a world (the same
 * minute after which Minecraft lowers its own frame rate), the world is drawn once more and every frame after is made from that
 * picture (Frame Generation's Ultra, held: {@link FrameGen#hold}); the camera may still move (a water current, a minecart).
 * Then, with nothing drawing the world:
 * <ul>
 * <li>the held picture's colour goes into a texture the GPU keeps compressed: Metal's lossy compression, done and undone by
 * the graphics chip's own hardware as it is written and read (Apple8 GPUs: M2 and later), half the memory; the picture before
 * it is let go;
 * <li>a shader pack's render targets are let go (often hundreds of MB at a Retina size);
 * <li>the Java heap is collected once and shrunk to what is live (HeapTrim.away), and the native allocator's free pages are
 * returned (NativeTrim).
 * </ul>
 * Any input brings it all back: the next frame draws the world again (a pack's targets are made again, as after a resize).
 * macOS's own memory compression is software on the processor and can't be asked for by an app (madvise MADV_PAGEOUT is
 * refused), and there is no other compression hardware an app can reach, so this is what the Mac's hardware offers.
 * -Dcadence.afkMemory=false: off; -Dcadence.afkMemory.seconds=N: after N seconds (60). Self-tests: only by the "afk=" step.
 */
public final class AfkMemory {
	private static final Logger LOGGER = LoggerFactory.getLogger("cadence");
	// (not by itself in self-tests: they send no input, and a benchmark would end up measuring the held picture; "afk=on" there)
	private static final boolean ENABLED = !"false".equals(System.getProperty("cadence.afkMemory")) && System.getProperty("cadence.selftest") == null;
	private static final long AFTER = Math.max(5, Long.getLong("cadence.afkMemory.seconds", 60)) * 1_000_000_000L;
	/** Self-tests: away now (true), back (false), or as the input says (null). */
	public static volatile Boolean forced;

	private static volatile long lastInput = System.nanoTime();
	private static boolean away;
	private static int step;
	private static long awaySince, stepAt;
	private static long beforeFootprint, beforeGpu, beforeHeap;
	private static long freedPicture, freedPack;
	private static volatile String last = "";

	private AfkMemory() {
	}

	/** FramerateLimitTracker.onInputReceived: the player did something. */
	public static void input() {
		lastInput = System.nanoTime();
	}

	public static boolean away() {
		return away;
	}

	/** What the last time away gave back (self-tests, the log). */
	public static String last() {
		return last;
	}

	/** Every frame (FramerateCapMixin, render thread). */
	public static void frame() {
		Minecraft mc = Minecraft.getInstance();
		long now = System.nanoTime();
		boolean want = forced != null ? forced : ENABLED && mc.level != null && now - lastInput > AFTER;
		if (want && mc.level == null) want = false;
		if (want != away) {
			if (want) enter(now);
			else leave(now);
		}
		if (away) steps(now);
	}

	private static void enter(long now) {
		away = true;
		step = 0;
		awaySince = stepAt = now;
		freedPicture = freedPack = 0;
		beforeFootprint = MacSystem.footprint();
		beforeGpu = gpu();
		Runtime rt = Runtime.getRuntime();
		beforeHeap = rt.totalMemory();
		FrameGen.hold = true;
		LOGGER.info("[cadence] Away: holding the picture; memory {} MB (GPU {} MB, Java heap {} MB committed)", mb(beforeFootprint), mb(beforeGpu), mb(beforeHeap));
	}

	/** Once the held frames are being generated: compress the picture, let the pack's targets go, then trim. */
	private static void steps(long now) {
		if (step == 0 && FrameGen.generating()) {
			freedPicture = FrameGen.holdCompress();
			ShaderRuntime runtime = ShaderRuntime.get();
			if (runtime != null) freedPack = runtime.releaseTargets();
			HeapTrim.away(true);
			NativeTrim.purge();
			step = 1;
			stepAt = now;
		} else if (step == 1 && now - stepAt > 5_000_000_000L) {
			// (measured once the collection and macOS's accounting have settled)
			NativeTrim.purge();
			long footprint = MacSystem.footprint(), gpuNow = gpu(), heap = Runtime.getRuntime().totalMemory();
			last = String.format(Locale.ROOT, "memory %d -> %d MB (GPU %d -> %d MB: picture compressed -%d MB, pack targets -%d MB; Java heap %d -> %d MB committed)",
				mb(beforeFootprint), mb(footprint), mb(beforeGpu), mb(gpuNow), mb(freedPicture), mb(freedPack), mb(beforeHeap), mb(heap));
			LOGGER.info("[cadence] Away: {}", last);
			step = 2;
		}
	}

	private static void leave(long now) {
		away = false;
		FrameGen.endHold();
		HeapTrim.away(false);
		LOGGER.info("[cadence] Back after {} s: drawing the world again", (now - awaySince) / 1_000_000_000L);
	}

	private static long gpu() {
		try {
			return GpuMemory.allocatedBytes();
		} catch (RuntimeException | LinkageError e) {
			return -1;
		}
	}

	private static long mb(long bytes) {
		return bytes < 0 ? -1 : bytes >> 20;
	}
}
