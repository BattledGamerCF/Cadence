package dev.cadence.shaders.cadence;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.jemalloc.JEmalloc;

/**
 * Cadence: native memory that LWJGL's allocator (jemalloc, Minecraft's default) keeps after it was freed. Vertex builders,
 * mesh uploads and texture loads allocate and free large native blocks; jemalloc keeps freed pages ("dirty") for reuse and
 * returns them to the system only slowly, so on an 8 GB Mac they sit in RAM or swap. {@link #purge} hands every arena's
 * unused dirty pages back (madvise); live allocations are untouched and the next allocation simply maps fresh pages.
 * Called by HeapTrim every 30 s (Macs with 16 GB or less). Measured stand-alone: 200 MB freed left the footprint 62 MB
 * higher until the purge. (LWJGL's jemalloc is built without statistics, so the game can't log how much is kept.)
 * -Dcadence.nativeTrim=false: off.
 */
public final class NativeTrim {
	private static final boolean ENABLED = !"false".equals(System.getProperty("cadence.nativeTrim"));
	private static Boolean jemalloc;

	private NativeTrim() {
	}

	/** Whether LWJGL allocates through jemalloc here (else nothing to do). */
	public static boolean available() {
		if (jemalloc == null) {
			boolean is;
			try {
				is = MemoryUtil.getAllocator().getClass().getName().contains("JEmalloc");
			} catch (Throwable e) {
				is = false;
			}
			jemalloc = is;
		}
		return ENABLED && jemalloc;
	}

	/** Returns every arena's unused dirty pages to the system. */
	public static void purge() {
		if (!available()) return;
		try {
			// MALLCTL_ARENAS_ALL = 4096
			JEmalloc.je_mallctl("arena.4096.purge", null, null, null);
		} catch (Throwable e) {
			jemalloc = false;
		}
	}
}
