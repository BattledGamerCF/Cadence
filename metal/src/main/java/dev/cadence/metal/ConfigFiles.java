package dev.cadence.metal;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Cadence: properties files read so that one bad line can never stop the game. Properties.load throws on a malformed \\u
 * escape (a hand-edited or damaged config), and some of these files are read while Minecraft is starting, where an exception
 * fails the launch ("A mod crashed on startup"). Here a file that doesn't load whole is read line by line and only the bad
 * lines are skipped, with a warning.
 */
public final class ConfigFiles {
	/** (each damaged file content warned about once, not at every read) */
	private static final java.util.Set<Integer> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private ConfigFiles() {
	}

	/**
	 * Properties saved whole or not at all: written to a file beside the real one, then moved over it, so a full disk or a game
	 * killed mid-write leaves the old settings instead of an empty or cut-off file. chars: Properties.store(Writer) as UTF-8
	 * text (Cadence's own files); otherwise store(OutputStream), Properties' ISO-8859-1 byte format (files Iris also reads).
	 */
	public static void store(Properties p, java.nio.file.Path file, String comment, boolean chars) throws IOException {
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		if (chars) {
			try (java.io.Writer w = new java.io.OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
				p.store(w, comment);
			}
		} else p.store(bytes, comment);
		if (file.getParent() != null) java.nio.file.Files.createDirectories(file.getParent());
		// (a unique temporary name: two saves of the same file at once from different threads shared ".tmp" before)
		java.nio.file.Path tmp = java.nio.file.Files.createTempFile(file.getParent() == null ? java.nio.file.Path.of(".") : file.getParent(),
			file.getFileName().toString(), ".tmp");
		java.nio.file.Files.write(tmp, bytes.toByteArray());
		try {
			java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static void load(Properties into, Reader reader) throws IOException {
		StringBuilder b = new StringBuilder();
		char[] buf = new char[8192];
		for (int n; (n = reader.read(buf)) > 0; ) b.append(buf, 0, n);
		loadText(into, b.toString());
	}

	/** Bytes in Properties' own format (ISO-8859-1 with \\u escapes), as Properties.load(InputStream). */
	public static void load(Properties into, InputStream in) throws IOException {
		loadText(into, new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
	}

	private static void loadText(Properties into, String text) throws IOException {
		try {
			Properties whole = new Properties();
			whole.load(new StringReader(text));
			into.putAll(whole);
			return;
		} catch (IllegalArgumentException e) {
			if (WARNED.add(text.hashCode())) System.out.println("[cadence] a config file has a malformed line (" + e.getMessage() + "); skipping the bad lines");
		}
		for (String line : text.split("\\R")) {
			try {
				Properties one = new Properties();
				one.load(new StringReader(line));
				into.putAll(one);
			} catch (IllegalArgumentException ignored) {
				// the bad line
			}
		}
	}
}
