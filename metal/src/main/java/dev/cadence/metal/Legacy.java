package dev.cadence.metal;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cadence: compatibility with the names this project had before it was renamed (mcopt and the Minecraft Shader Loader).
 * Nothing here changes behaviour for anyone who already uses the new names.
 * <ul>
 * <li>System properties: {@code -Dmcopt.X} still works as {@code -Dcadence.X}, and {@code -DminecraftShaders.X} as
 * {@code -Dcadence.shaders.X}, {@code -DminecraftScene.X} as {@code -Dcadence.scene.X}. {@link #migrateProperties()} copies each old key to its new name when the new name is
 * absent (a new key always wins), so the rest of the code reads only the new names. It runs first thing in
 * {@link Profile#apply()} and at the start of every mixin config plugin; later calls are cheap and idempotent.</li>
 * <li>Config files: {@code config/mcopt.properties} is read as {@code config/cadence.properties} and
 * {@code config/minecraft-shader-loader.properties} as {@code config/cadence-shaders.properties} while the new file does
 * not exist yet ({@link #readable}); every save goes to the new name (so a first save copies the old settings across),
 * and the old file is never changed or deleted.</li>
 * </ul>
 */
public final class Legacy {
	/** Old file names (in the game's config folder) and the names that replace them. */
	public static final String CONFIG = "cadence.properties", CONFIG_OLD = "mcopt.properties";
	public static final String SHADERS_CONFIG = "cadence-shaders.properties", SHADERS_CONFIG_OLD = "minecraft-shader-loader.properties";

	private static final String OLD_PREFIX = "mcopt.", NEW_PREFIX = "cadence.";
	private static final String OLD_SHADERS_PREFIX = "minecraftShaders.", NEW_SHADERS_PREFIX = "cadence.shaders.";
	private static final String OLD_SCENE_PREFIX = "minecraftScene.", NEW_SCENE_PREFIX = "cadence.scene.";

	private Legacy() {
	}

	/** The new key for an old-style property name, or null when {@code key} is not an old-style name. */
	public static String modernKey(String key) {
		if (key.startsWith(OLD_PREFIX)) return NEW_PREFIX + key.substring(OLD_PREFIX.length());
		if (key.startsWith(OLD_SHADERS_PREFIX)) return NEW_SHADERS_PREFIX + key.substring(OLD_SHADERS_PREFIX.length());
		if (key.startsWith(OLD_SCENE_PREFIX)) return NEW_SCENE_PREFIX + key.substring(OLD_SCENE_PREFIX.length());
		return null;
	}

	/** Copies every old-style system property to its new name unless the new name is already set. Safe to call any number of times. */
	public static void migrateProperties() {
		for (String k : System.getProperties().stringPropertyNames()) {
			String n = modernKey(k);
			if (n != null && System.getProperty(n) == null) System.setProperty(n, System.getProperty(k));
		}
	}

	/**
	 * The file to read a config from: {@code dir/name}, or {@code dir/oldName} when only that one exists. When neither exists it is
	 * {@code dir/name} (the file a save creates).
	 */
	public static Path readable(Path dir, String name, String oldName) {
		Path now = dir.resolve(name);
		if (Files.exists(now)) return now;
		Path old = dir.resolve(oldName);
		return Files.exists(old) ? old : now;
	}
}
