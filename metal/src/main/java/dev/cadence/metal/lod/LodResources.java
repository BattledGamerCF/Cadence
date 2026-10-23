package dev.cadence.metal.lod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * The resource packs the far terrain's colors come from. A tile's colors are worked out from the block textures when it is
 * made, so each cached tile records the packs it was made under (signature()), and a resource reload (the Resource Packs
 * screen, F3+T) has the colors, the texture table and, under other packs, a generated world's tiles made again. The far view
 * used to keep the first pack's colors for good, also across restarts (BH2-18).
 */
final class LodResources {
	private static volatile boolean reloaded;
	private static boolean listening;

	private LodResources() {
	}

	/** Listens for resource reloads from now on (render thread; once a session). */
	static void listen(Minecraft mc) {
		if (listening) return;
		listening = true;
		if (mc.getResourceManager() instanceof ReloadableResourceManager r) r.registerReloadListener((ResourceManagerReloadListener) rm -> reloaded = true);
	}

	/**
	 * True once after a resource reload, when it has finished (its loading overlay gone: the block atlas and models are the
	 * new ones by then, whatever order the reload ran its parts in).
	 */
	static boolean takeReload(Minecraft mc) {
		if (!reloaded || mc.gui.overlay() != null) return false;
		reloaded = false;
		return true;
	}

	/** The selected resource packs: their names, and a pack file's size and date (a pack updated in place counts as another). */
	static int signature(Minecraft mc) {
		int h = 1;
		Path dir = mc.getResourcePackDirectory();
		for (String id : mc.getResourcePackRepository().getSelectedIds()) {
			h = 31 * h + id.hashCode();
			if (!id.startsWith("file/")) continue;
			try {
				BasicFileAttributes a = Files.readAttributes(dir.resolve(id.substring(5)), BasicFileAttributes.class);
				h = 31 * h + Long.hashCode(a.size());
				h = 31 * h + Long.hashCode(a.lastModifiedTime().toMillis());
			} catch (IOException | RuntimeException e) {
				// (a pack the repository still lists but the folder no longer has: its name alone)
			}
		}
		return h;
	}
}
