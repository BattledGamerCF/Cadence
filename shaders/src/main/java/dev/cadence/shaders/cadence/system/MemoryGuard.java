package dev.cadence.shaders.cadence.system;

import dev.cadence.shaders.cadence.NativeTrim;
import dev.cadence.shaders.cadence.RenderScale;
import dev.cadence.metal.MacSystem;
import org.slf4j.LoggerFactory;

/**
 * Cadence: when macOS says memory is getting tight (its memory-pressure signal, the same one that precedes heavy swapping
 * on an 8 GB Mac), Cadence gives memory back at once instead of waiting: freed native memory is returned (NativeTrim),
 * the Java heap's soft limit is pulled in to what is live (HeapTrim), and chunk geometry heaps are compacted more eagerly.
 * At critical pressure Auto World Resolution is also held at 85% or below (smaller images). Back to normal when macOS
 * reports normal again. Always on (it only reacts to macOS's own signal); every change is shown on screen.
 */
public final class MemoryGuard {
	private static volatile int level;
	/** Self-test: a pretend memory-pressure level (-1 = the real one). */
	public static volatile int fakePressure = -1;

	private MemoryGuard() {
	}

	public static int level() {
		return level;
	}

	/** SystemMonitor, every 2 s. */
	static void poll() {
		int now = fakePressure >= 0 ? fakePressure : MacSystem.memoryPressure();
		if (now == level) {
			if (now > 0) NativeTrim.purge();
			return;
		}
		int previous = level;
		level = now;
		LoggerFactory.getLogger("cadence").info("Memory guard: macOS memory pressure {} -> {}", previous, now);
		dev.cadence.shaders.cadence.HeapTrim.pressure(now);
		dev.cadence.shaders.cadence.memory.ChunkHeapCompactor.pressure(now);
		NativeTrim.purge();
		if (now >= 2) RenderScale.setCeiling(Math.min(RenderScale.ceiling(), 0.85f));
		else if (HeatControl.level() < 2) RenderScale.setCeiling(1.0f);
		if (now > previous) Notices.post("Your Mac is low on memory", describe());
		else if (now == 0) Notices.post("Memory is fine again", "Cadence is back to normal");
	}

	/** One short line for the settings screen. */
	public static String shortDescribe() {
		return level == 0 ? "" : level == 1 ? "Low memory: returning memory to macOS" : "Very low memory: returning memory, lower resolution";
	}

	/** What the guard is doing, in plain words ("" when nothing). */
	public static String describe() {
		return switch (level) {
			case 0 -> "";
			case 1 -> "macOS memory is tight: Cadence is returning unused memory";
			default -> "macOS memory is very tight: returning memory, Auto resolution at most 85%";
		};
	}
}
