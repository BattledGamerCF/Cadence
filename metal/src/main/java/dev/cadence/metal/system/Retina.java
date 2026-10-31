package dev.cadence.metal.system;

/**
 * Cadence: whether the game window uses the display's full Retina resolution (the default) or its point size. Off, every pixel
 * the game draws (world, hand, HUD, menus) is a 2x2 block on a Retina display: a quarter of the pixels, which macOS scales up
 * when it shows the window. Read once, when the window is made: -Dcadence.retina=false, or retina=false in
 * config/cadence-shaders.properties (the Video Settings button; it takes effect at the next launch).
 */
public final class Retina {
	private static Boolean on;

	private Retina() {
	}

	public static boolean on() {
		if (on == null) {
			String p = System.getProperty("cadence.retina");
			if (p == null) {
				try {
					var file = Legacy.readable(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(), Legacy.SHADERS_CONFIG, Legacy.SHADERS_CONFIG_OLD);
					if (java.nio.file.Files.isRegularFile(file)) {
						java.util.Properties props = new java.util.Properties();
						try (var in = java.nio.file.Files.newInputStream(file)) {
							ConfigFiles.load(props, in);
						}
						p = props.getProperty("retina");
					}
				} catch (Exception e) {
					// unreadable: the default
				}
			}
			on = !"false".equalsIgnoreCase(p == null ? "" : p.strip());
		}
		return on;
	}
}
