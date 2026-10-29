package dev.cadence.metal.neural;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Cadence Helper (src/main/helper): a small app shipped in the jar for the jobs that need their own process: Create ML training
 * (Style Filters) and on-device speech recognition (Voice Control: macOS asks for the microphone and speech recognition for the
 * helper, which has the usage descriptions; asked for by Java they'd end the game). Unpacked once per version into
 * ~/Library/Application Support/Cadence/Cadence Helper.app.
 */
public final class Helper {
	private static final String[] FILES = {"Contents/Info.plist", "Contents/MacOS/cadence-helper", "Contents/_CodeSignature/CodeResources"};
	private static Path app;

	private Helper() {
	}

	/** The app bundle, unpacked (null if it can't be). */
	public static synchronized Path app() {
		if (app != null) return app;
		try {
			Path dir = Path.of(System.getProperty("user.home"), "Library", "Application Support", "Cadence");
			Path target = dir.resolve("Cadence Helper.app");
			// (a hash of what is in the jar: any new build of the app or its Info.plist unpacks again, BH7-17)
			String stamp = digest();
			Path stampFile = dir.resolve("helper-stamp.txt");  // (outside the bundle: its signature seals what is inside)
			// (and when the app itself is gone: everything in that folder is documented as safe to delete)
			if (!Files.exists(stampFile) || !Files.readString(stampFile).equals(stamp)
				|| !Files.isExecutable(target.resolve("Contents/MacOS/cadence-helper"))) {
				Files.createDirectories(dir);
				for (String f : FILES) {
					try (InputStream in = Helper.class.getResourceAsStream("/natives/CadenceHelper.app/" + f)) {
						if (in == null) {
							if (f.contains("_CodeSignature")) continue;
							throw new IOException("the jar has no Cadence Helper (" + f + ")");
						}
						Path out = target.resolve(f);
						Files.createDirectories(out.getParent());
						Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
					}
				}
				target.resolve("Contents/MacOS/cadence-helper").toFile().setExecutable(true, false);
				Files.writeString(stampFile, stamp);
			}
			app = target;
			return app;
		} catch (IOException e) {
			System.out.println("[cadence] Cadence Helper unavailable: " + e);
			return null;
		}
	}

	/** The helper's executable (run directly for jobs that need no permissions). */
	public static Path binary() {
		Path a = app();
		return a == null ? null : a.resolve("Contents/MacOS/cadence-helper");
	}

	private static String digest() throws IOException {
		try {
			var md = java.security.MessageDigest.getInstance("SHA-256");
			for (String f : FILES) {
				try (InputStream in = Helper.class.getResourceAsStream("/natives/CadenceHelper.app/" + f)) {
					if (in != null) md.update(in.readAllBytes());
				}
			}
			return java.util.HexFormat.of().formatHex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}
}
