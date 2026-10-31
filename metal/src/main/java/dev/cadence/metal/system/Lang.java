package dev.cadence.metal.system;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Translations of Cadence's own text into the language picked in Minecraft (Options > Language). Cadence's text is written in
 * English in the code; the English is the key. Each language is a file {@code cadence/lang/<code>.json} mapping English to
 * the translation. A key with {} is a template for text put together from parts ("Performance: {}"): the parts are
 * translated in turn, and a translation may place them in another order with {1}, {2}. Text with no entry stays English.
 * Languages without a file use a close one (es_ar: es_mx; fr_ca: fr_fr); English (en_gb) is the text as written, en_us
 * has American spellings. -Dcadence.lang=CODE shows a language's text whatever Minecraft's language is (checks, screenshots).
 */
public final class Lang {
	private static final Map<String, String> FALLBACK = Map.ofEntries(
		Map.entry("es_ar", "es_mx"), Map.entry("es_cl", "es_mx"), Map.entry("es_ec", "es_mx"), Map.entry("es_uy", "es_mx"),
		Map.entry("es_ve", "es_mx"), Map.entry("fr_ca", "fr_fr"), Map.entry("pt_pt", "pt_br"), Map.entry("zh_hk", "zh_tw"),
		Map.entry("lzh", "zh_tw"), Map.entry("en_ca", "en_us"), Map.entry("en_au", "en_gb"), Map.entry("en_nz", "en_gb"),
		Map.entry("de_at", "de_de"), Map.entry("de_ch", "de_de"), Map.entry("nl_be", "nl_nl"));
	private static final int CACHE_LIMIT = 4096;

	private static String code;
	private static Map<String, String> exact = Map.of();
	private static List<Template> templates = List.of();
	private static final Map<String, String> cache = new HashMap<>();

	private Lang() {
	}

	/** {@code Component.literal} of the text in the player's language. */
	public static MutableComponent literal(String english) {
		return Component.literal(tr(english));
	}

	/** The text in the player's language (the English when there's no translation). */
	public static synchronized String tr(String english) {
		if (english == null || english.isEmpty()) return english;
		select();
		if (exact.isEmpty() && templates.isEmpty()) return english;
		String done = cache.get(english);
		if (done != null) return done;
		done = translate(english, 0);
		if (cache.size() >= CACHE_LIMIT) cache.clear();
		cache.put(english, done);
		return done;
	}

	private static String translate(String s, int depth) {
		String t = exact.get(s);
		if (t != null) return t;
		// leading and trailing spaces and punctuation kept as written, the words between translated
		int a = 0, b = s.length();
		while (a < b && " :;,.()·\n".indexOf(s.charAt(a)) >= 0) a++;
		while (b > a && " :;,.()·\n".indexOf(s.charAt(b - 1)) >= 0) b--;
		if (a == b) return s;
		String core = s.substring(a, b);
		if (a > 0 || b < s.length()) {
			t = exact.get(core);
			if (t != null) return s.substring(0, a) + t + s.substring(b);
		}
		if (depth < 4) {
			for (Template template : templates) {
				Matcher m = template.pattern.matcher(core);
				if (m.matches()) return s.substring(0, a) + template.fill(m, depth) + s.substring(b);
			}
		}
		return s;
	}

	/** The selected language's file, loaded when the language changes. */
	private static void select() {
		String now = System.getProperty("cadence.lang");  // (-Dcadence.lang=de_de: a language's text without switching Minecraft's)
		if (now == null) try {
			Minecraft mc = Minecraft.getInstance();
			now = mc == null || mc.getLanguageManager() == null ? "en_us" : mc.getLanguageManager().getSelected();
		} catch (RuntimeException | LinkageError e) {
			now = "en_us";
		}
		if (now.equals(code)) return;
		code = now;
		cache.clear();
		exact = Map.of();
		templates = List.of();
		JsonObject json = load(now);
		if (json == null && FALLBACK.containsKey(now)) json = load(FALLBACK.get(now));
		if (json == null) return;
		Map<String, String> e = new HashMap<>();
		List<Template> ts = new ArrayList<>();
		for (var entry : json.entrySet()) {
			if (!entry.getValue().isJsonPrimitive()) continue;
			String key = entry.getKey(), value = entry.getValue().getAsString();
			if (key.contains("{}")) ts.add(new Template(key, value));
			else e.put(key, value);
		}
		ts.sort((x, y) -> Integer.compare(y.literalLength, x.literalLength));  // (the most specific first)
		exact = e;
		templates = ts;
	}

	private static JsonObject load(String code) {
		try (InputStream in = Lang.class.getResourceAsStream("/cadence/lang/" + code + ".json")) {
			if (in == null) return null;
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception ex) {
			System.out.println("[cadence] can't read translations for " + code + ": " + ex);
			return null;
		}
	}

	private static final class Template {
		final Pattern pattern;
		final String value;
		final int literalLength;

		Template(String key, String value) {
			StringBuilder p = new StringBuilder();
			String[] parts = key.split("\\{\\}", -1);
			for (int i = 0; i < parts.length; i++) {
				if (i > 0) p.append("(.*?)");
				p.append(Pattern.quote(parts[i]));
			}
			this.pattern = Pattern.compile(p.toString(), Pattern.DOTALL);
			this.value = value;
			this.literalLength = key.length() - 2 * (parts.length - 1);
		}

		String fill(Matcher m, int depth) {
			String[] parts = new String[m.groupCount()];
			for (int i = 0; i < parts.length; i++) parts[i] = translate(m.group(i + 1), depth + 1);
			StringBuilder out = new StringBuilder();
			int next = 0;
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				if (c == '{' && i + 1 < value.length()) {
					int close = value.indexOf('}', i);
					String inside = close > i ? value.substring(i + 1, close) : null;
					if (inside != null && inside.isEmpty() && next < parts.length) {
						out.append(parts[next++]);
						i = close;
						continue;
					}
					if (inside != null && inside.matches("\\d") && Integer.parseInt(inside) - 1 < parts.length && Integer.parseInt(inside) >= 1) {
						out.append(parts[Integer.parseInt(inside) - 1]);
						i = close;
						continue;
					}
				}
				out.append(c);
			}
			return out.toString();
		}
	}
}
