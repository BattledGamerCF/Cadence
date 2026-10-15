// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * -Dcadence.own.int.visible=true (or =verify): positions in vanilla's visible-section list, recorded while vanilla fills it
 * (SectionOcclusionGraph.addSectionsInFrustum, one int store per section), so per-frame work that only concerns a few sections
 * can take them in vanilla's order without walking the whole list. Used by OwnIntBlockEntitiesMixin: the sections whose current
 * mesh has block entities (a set kept from RenderSection.setSectionMesh, any thread) are filtered to this generation's visible
 * ones and sorted by their position: the same sections in the same order as vanilla's walk over every visible section.
 */
public final class OwnVisible {
	public static final String MODE = System.getProperty("cadence.own.int.visible", "false");
	public static final boolean ON = "true".equals(MODE), VERIFY = "verify".equals(MODE);

	/** Bumped whenever vanilla clears its visible list; a section is visible iff its stamp equals it. */
	private static int generation = 1;
	private static int[] position = new int[0], stamp = new int[0];
	/** Sections (by RenderSection.index) whose mesh had block entities when it was set; may hold sections that no longer do. */
	private static final ConcurrentHashMap<SectionRenderDispatcher.RenderSection, Boolean> WITH_BE = new ConcurrentHashMap<>();
	private static SectionRenderDispatcher.RenderSection[] scratch = new SectionRenderDispatcher.RenderSection[64];
	/** How many leading scratch slots the last visibleWithBlockEntities call filled (the rest are null). */
	private static int filled;

	private OwnVisible() {
	}

	/** Render thread: vanilla cleared its visible list. */
	public static void cleared() {
		generation++;
	}

	/** Render thread: vanilla is adding section at position (the list's size before the add). */
	public static void added(SectionRenderDispatcher.RenderSection section, int at) {
		int i = section.index;
		if (i >= stamp.length) {
			int cap = Math.max(1024, Integer.highestOneBit(i) * 2);
			stamp = Arrays.copyOf(stamp, cap);
			position = Arrays.copyOf(position, cap);
		}
		stamp[i] = generation;
		position[i] = at;
	}

	/** Any thread: a section's mesh was set; remember it if the new mesh renders block entities. */
	public static void meshSet(SectionRenderDispatcher.RenderSection section) {
		if (!section.getSectionMesh().getRenderableBlockEntities().isEmpty()) WITH_BE.put(section, Boolean.TRUE);
	}

	/**
	 * Render thread: the visible sections that may have block entities, in vanilla's visible order. count receives the number of
	 * sections in the returned array (a reused scratch array). Sections without block entities any more are dropped from the set
	 * when they are also not visible (a later mesh with block entities puts them back through meshSet).
	 */
	public static SectionRenderDispatcher.RenderSection[] visibleWithBlockEntities(int[] count) {
		int n = 0, g = generation;
		for (SectionRenderDispatcher.RenderSection s : WITH_BE.keySet()) {
			int i = s.index;
			boolean visible = i < stamp.length && stamp[i] == g;
			if (!visible) {
				if (s.getSectionMesh().getRenderableBlockEntities().isEmpty()) {
					WITH_BE.remove(s, Boolean.TRUE);
					// a worker may have set a mesh with block entities since the read: its meshSet ran before the removal, so look again
					if (!s.getSectionMesh().getRenderableBlockEntities().isEmpty()) WITH_BE.put(s, Boolean.TRUE);
				}
				continue;
			}
			if (n == scratch.length) scratch = Arrays.copyOf(scratch, n * 2);
			scratch[n++] = s;
		}
		int[] pos = position;
		Arrays.sort(scratch, 0, n, (a, b) -> Integer.compare(pos[a.index], pos[b.index]));
		// the slots past n still hold earlier calls' sections (of earlier worlds too, after a level change): clear them so the scratch
		// array doesn't keep those sections and everything they reach (their dispatcher, buffers, meshes, block entities, level) alive
		if (n < filled) Arrays.fill(scratch, n, filled, null);
		filled = n;
		count[0] = n;
		return scratch;
	}

	/**
	 * Render thread: vanilla dropped its ViewArea and with it every RenderSection (LevelRenderer.invalidateCompiledGeometry on joining a
	 * level or a renderer reload, resetLevelRenderData on leaving one): forget them. Nothing visible changes (none of those sections
	 * can be visible again, and the new ones enter the set through meshSet when their meshes are set); it only stops this class from
	 * keeping the old sections, and through them the old level, alive.
	 */
	public static void levelReset() {
		WITH_BE.clear();
		scratch = new SectionRenderDispatcher.RenderSection[64];
		filled = 0;
	}

	/**
	 * Render thread: the slots set in candidates that are visible this generation, in vanilla's visible order REVERSED (far to
	 * near, as our translucent draw walks the list), into a reused array; count receives the number taken.
	 */
	public static int[] visibleSlotsReversed(java.util.BitSet candidates, int candidatesVersion, int[] count) {
		int g = generation;
		// the order only changes with a new visible list or a change of the candidate set: reuse it otherwise
		if (g == cachedGeneration && candidatesVersion == cachedVersion) {
			count[0] = cachedCount;
			return slotScratch;
		}
		int n = 0;
		for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
			if (i >= stamp.length || stamp[i] != g) continue;
			if (n == slotScratch.length) slotScratch = Arrays.copyOf(slotScratch, n * 2);
			slotScratch[n++] = i;
		}
		// sort by position, descending (positions are distinct within a generation): a small array, insertion-free via keys
		long[] keys = n <= keyScratch.length ? keyScratch : (keyScratch = new long[Integer.highestOneBit(n) * 2]);
		for (int j = 0; j < n; j++) keys[j] = (long) position[slotScratch[j]] << 32 | slotScratch[j];
		Arrays.sort(keys, 0, n);
		for (int j = 0; j < n; j++) slotScratch[j] = (int) keys[n - 1 - j];
		count[0] = n;
		cachedGeneration = g;
		cachedVersion = candidatesVersion;
		cachedCount = n;
		return slotScratch;
	}

	private static int cachedGeneration = -1, cachedVersion = -1, cachedCount;
	private static int[] slotScratch = new int[256];
	private static long[] keyScratch = new long[256];
}
