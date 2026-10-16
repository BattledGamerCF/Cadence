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
