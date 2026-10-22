package dev.cadence.metal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cadence: keeps a disk cache folder (cadence-cache/spirv, cadence-cache/msl) under a size budget, the least recently used
 * entries going first. Their keys include a salt of the translator's code, so every Cadence release that changes it orphans
 * the old entries, and every pack and quality profile adds more: without a budget they grew without end (727 MB after ten
 * days of testing). Pruned once per folder per session, on a background thread; a hit refreshes its file's time (at most once
 * a day) so what is still used stays.
 */
public final class DiskCacheBudget {
	private static final Set<Path> PRUNED = ConcurrentHashMap.newKeySet();
	private static final long DAY = 24L * 3600 * 1000;

	private DiskCacheBudget() {
	}

	/** Once per session per folder, in the background: the folder trimmed to 3/4 of budget when it is over budget. */
	public static void pruneOnce(Path dir, long budget) {
		if (dir == null || !PRUNED.add(dir)) return;
		Thread t = new Thread(() -> prune(dir, budget), "cadence-cache-prune");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	/** A cache hit: its time refreshed (if older than a day), so pruning keeps it. */
	public static void used(Path file) {
		try {
			long now = System.currentTimeMillis();
			if (now - Files.getLastModifiedTime(file).toMillis() > DAY) Files.setLastModifiedTime(file, FileTime.fromMillis(now));
		} catch (IOException | RuntimeException ignored) {
		}
	}

	static void prune(Path dir, long budget) {
		if (!Files.isDirectory(dir)) return;
		List<Path> all = new ArrayList<>();
		Map<Path, long[]> info = new HashMap<>();  // size, last used
		long total = 0;
		try (var files = Files.list(dir)) {
			for (Path p : (Iterable<Path>) files::iterator) {
				if (!Files.isRegularFile(p)) continue;
				long size = Files.size(p);
				// (a .tmp left by a killed write an hour or more ago: never completed; a fresh one may be a write in progress)
				if (p.getFileName().toString().endsWith(".tmp")) {
					if (System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() > 3600_000L) Files.deleteIfExists(p);
					continue;
				}
				all.add(p);
				info.put(p, new long[] {size, Files.getLastModifiedTime(p).toMillis()});
				total += size;
			}
		} catch (IOException | RuntimeException e) {
			return;  // a cache that can't be pruned is only bigger
		}
		if (total <= budget) return;
		all.sort(Comparator.comparingLong(p -> info.get(p)[1]));
		long before = total;
		int removed = 0;
		for (Path p : all) {
			if (total <= budget * 3 / 4) break;
			try {
				Files.deleteIfExists(p);
				total -= info.get(p)[0];
				removed++;
			} catch (IOException ignored) {
			}
		}
		System.out.println("[cadence] cache " + dir.getFileName() + ": " + removed + " old entries removed, " + (before >> 20) + " -> "
			+ (total >> 20) + " MB (budget " + (budget >> 20) + " MB)");
	}
}
