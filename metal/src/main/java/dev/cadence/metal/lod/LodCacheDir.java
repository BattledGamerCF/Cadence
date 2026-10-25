package dev.cadence.metal.lod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Where a world's far terrain is kept on disk: `cadence-lod/<world>-<seed>/<dimension>/` in single player (and for a LAN host),
 * `cadence-lod/servers/<address>-<hashed seed>/<dimension>/` on a server. The seed tells two worlds of the same name apart:
 * keyed by the folder name alone, a new "New World" showed the old one's land (BUG-HUNT-10-24 BH3-04); a LAN guest's address
 * is kept without the host's port, which is new every time the host opens to LAN. The dimension's folder name is made safe
 * (a server chooses it, and an id may hold `..` and `/`: BH3-09). Folders of the old layout are moved to the new one when
 * their world is opened, and at the first world of a session the folders of deleted single-player worlds are removed (with
 * any `.tmp` files a crash left), on a background thread.
 */
final class LodCacheDir {
	private LodCacheDir() {
	}

	private static final Pattern KEYED = Pattern.compile("(.+)-([0-9a-f]{1,16})");
	private static boolean pruned;

	/** The cache folder for this level, or null when none can be made safely. */
	static @Nullable Path of(Minecraft mc, ClientLevel level, @Nullable ServerLevel sl, Path root) {
		String save;
		if (sl != null) {
			String folder = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
				.toAbsolutePath().normalize().getFileName().toString();
			save = migrate(root, folder, folder + "-" + Long.toHexString(sl.getSeed()));
			if (LodConfig.CACHE_DIR == null) prune(mc, root);
		} else {
			var info = mc.getCurrentServer();
			String address = info != null ? info.ip : "server";
			if (info != null && info.isLan()) {
				int colon = address.lastIndexOf(':');
				if (colon > 0) address = address.substring(0, colon);
			}
			String legacy = "servers/" + safe(address);
			long seed = level.getBiomeManager() instanceof dev.cadence.metal.mixin.lod.LodBiomeSeedAccess a ? a.cadence$biomeZoomSeed() : 0;
			save = migrate(root, legacy, legacy + "-" + Long.toHexString(seed));
		}
		var id = level.dimension().identifier();
		String dim = safe(id.getNamespace() + "_" + id.getPath());
		// (a changed name gets a hash of the id: two ids that differ only in the characters replaced stay apart)
		if (!dim.equals(id.getNamespace() + "_" + id.getPath())) dim += "-" + Integer.toHexString(id.toString().hashCode());
		Path cache = root.resolve(save).resolve(dim).normalize();
		if (!cache.startsWith(root.normalize())) {
			System.out.println("cadence-lod: no far-terrain cache for " + id + " (outside the cache folder)");
			return null;
		}
		return cache;
	}

	/** A name safe as one path component: lower case, [a-z0-9._-] (others to '_'), no leading dot. */
	static String safe(String name) {
		String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		while (s.startsWith(".")) s = "_" + s.substring(1);
		return s.isEmpty() ? "_" : s;
	}

	/** The old layout's folder (no seed) moved to the new name the first time its world is opened; returns the new name. */
	private static String migrate(Path root, String legacy, String keyed) {
		Path from = root.resolve(legacy), to = root.resolve(keyed);
		if (Files.isDirectory(from) && !Files.exists(to)) {
			try {
				Files.move(from, to);
				System.out.println("cadence-lod: cache folder " + legacy + " is now " + keyed);
			} catch (IOException e) {
				System.out.println("cadence-lod: couldn't move the cache folder " + legacy + ": " + e);
			}
		}
		return keyed;
	}

	/** Once a session: the folders of single-player worlds that no longer exist, and leftover .tmp files, removed. */
	private static void prune(Minecraft mc, Path root) {
		if (pruned) return;
		pruned = true;
		Path saves = mc.getLevelSource().getBaseDir();
		Thread t = new Thread(() -> {
			long freed = 0;
			int folders = 0;
			try (Stream<Path> top = Files.list(root)) {
				for (Path dir : (Iterable<Path>) top::iterator) {
					String name = dir.getFileName().toString();
					if (!Files.isDirectory(dir)) continue;
					if (name.equals("servers")) {
						freed += removeTmp(dir);
						continue;
					}
					Matcher m = KEYED.matcher(name);
					// (a world named like "base-1a" in the old layout: kept while either name is a save)
					boolean worldGone = !Files.isDirectory(saves.resolve(name)) && !(m.matches() && Files.isDirectory(saves.resolve(m.group(1))));
					if (worldGone) {
						freed += delete(dir, root);
						folders++;
					} else {
						freed += removeTmp(dir);
					}
				}
			} catch (IOException | RuntimeException e) {
				System.out.println("cadence-lod: cache clean-up stopped: " + e);
			}
			if (folders > 0 || freed > 0) System.out.printf("cadence-lod: cache clean-up: %d folders of deleted worlds, %.1f MB%n", folders, freed / 1048576.0);
		}, "cadence-lod-prune");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	/** Deletes a folder under root and everything in it; returns the bytes freed. */
	private static long delete(Path dir, Path root) throws IOException {
		Path r = root.toAbsolutePath().normalize(), d = dir.toAbsolutePath().normalize();
		if (!d.startsWith(r) || d.equals(r)) return 0;
		long[] bytes = {0};
		try (Stream<Path> all = Files.walk(d)) {
			all.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try {
					if (Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) bytes[0] += Files.size(p);
					Files.delete(p);
				} catch (IOException e) {
					// (in use or gone: left for the next session)
				}
			});
		}
		return bytes[0];
	}

	/** Removes the `<tile>.lod.tmp<n>` files a crash during a save left (saves write a tmp file, then move it). */
	private static long removeTmp(Path dir) throws IOException {
		long[] bytes = {0};
		try (Stream<Path> all = Files.walk(dir)) {
			all.filter(p -> p.getFileName().toString().contains(".lod.tmp")).forEach(p -> {
				try {
					// (only old ones: a save in progress writes one right now)
					if (System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() < 600_000) return;
					long n = Files.size(p);
					Files.delete(p);
					bytes[0] += n;
				} catch (IOException e) {
					// left for the next session
				}
			});
		}
		return bytes[0];
	}
}
