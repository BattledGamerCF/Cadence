package dev.cadence.shaders.cadence.shaderpack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Shader packs that ship inside Cadence (today: Cadence Lite). Each is kept as a normal zip in shaderpacks/, so the
 * Shader Packs screen, profiles and pack settings treat it like any other pack. Written only when missing or different.
 */
public final class BuiltinPacks {
	public static final String CADENCE_LITE = "Cadence Lite.zip";

	private BuiltinPacks() {}

	public static void install(Path packsDir) {
		try (InputStream in = BuiltinPacks.class.getResourceAsStream("/cadence/cadence-lite.zip")) {
			if (in == null) return;
			byte[] bytes = in.readAllBytes();
			Path target = packsDir.resolve(CADENCE_LITE);
			if (Files.isRegularFile(target) && Files.size(target) == bytes.length
					&& Arrays.equals(Files.readAllBytes(target), bytes)) return;
			Files.createDirectories(packsDir);
			Path tmp = Files.createTempFile(packsDir, ".cadence-lite-", ".tmp");
			try {
				Files.write(tmp, bytes);
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} finally {
				Files.deleteIfExists(tmp);
			}
		} catch (IOException e) {
			org.slf4j.LoggerFactory.getLogger("cadence_shaders").warn("Couldn't install Cadence Lite: {}", e.toString());
		}
	}
}
