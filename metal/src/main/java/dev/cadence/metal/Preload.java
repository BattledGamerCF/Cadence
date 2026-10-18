// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Loads, on background threads, the classes Minecraft is about to load one by one on its main thread during boot and
 * world load, in the order it loads them, so reading, Mixin-transforming and defining them overlaps the main thread's
 * own work instead of stalling it. Loading only (no static initializers run), so nothing about the game changes: the
 * same classes end up loaded, only sooner and off the main thread. The list (resources/cadence/metal/preload.txt.gz)
 * comes from a -Xlog:class+load run of the shipping stack (tools/preloadlist.py); names that don't exist in some
 * other mod set are skipped. -Dcadence.preload=false turns it off, -Dcadence.preload.threads=N sets the thread count.
 */
public final class Preload implements PreLaunchEntrypoint {
	static {
		dev.cadence.metal.Profile.apply(); // before any flag is read
	}

	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("cadence.preload", "true"));
	private static final int THREADS = Integer.getInteger("cadence.preload.threads", 2);

	@Override
	public void onPreLaunch() {
		PlatformCheck.run();
		if (!ENABLED) return;
		// Cadence: another mod's pre-launch step may still add mixin configs (Essential's loader does, and needs MixinExtras not
		// yet initialized): classes loaded now would apply mixins, MixinExtras' included, before it runs, and do so on other
		// threads while it selects its configs ("The MixinExtras service has already been selected", "Attempted to pop plugin
		// but the stack is empty": Essential didn't load). With such a mod present the preload waits for the client entrypoint,
		// after every pre-launch step (PreloadLater).
		if (otherPreLaunch()) {
			deferred = true;
			return;
		}
		start();
	}

	private static volatile boolean deferred;

	/** PreloadLater (client entrypoint): the deferred preload, once every mod's pre-launch step is done. */
	static void startDeferred() {
		if (deferred) {
			deferred = false;
			start();
		}
	}

	private static boolean otherPreLaunch() {
		try {
			for (var c : net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypointContainers("preLaunch", PreLaunchEntrypoint.class))
				if (!c.getProvider().getMetadata().getId().equals("cadence")) return true;
		} catch (RuntimeException e) {
			return true;  // (unknown: the safe side)
		}
		return false;
	}

	private static void start() {
		List<String> names = new ArrayList<>();
		try (InputStream in = Preload.class.getResourceAsStream("/cadence/metal/preload.txt.gz")) {
			if (in == null) return;
			BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
			for (String line; (line = r.readLine()) != null; ) if (!line.isEmpty()) names.add(line);
		} catch (IOException e) {
			return;
		}
		ClassLoader loader = Preload.class.getClassLoader();
		AtomicInteger next = new AtomicInteger();
		for (int t = 0; t < THREADS; t++) {
			Thread thread = new Thread(() -> {
				for (int i; (i = next.getAndIncrement()) < names.size(); ) {
					try {
						Class.forName(names.get(i), false, loader);
					} catch (Throwable absent) {
						// Not in this mod set or environment: the game wouldn't load it either.
					}
				}
			}, "Cadence class preload #" + t);
			thread.setDaemon(true);
			thread.start();
		}
	}
}
