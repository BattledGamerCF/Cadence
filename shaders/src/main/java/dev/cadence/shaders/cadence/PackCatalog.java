package dev.cadence.shaders.cadence;

import java.util.List;
import java.util.Locale;

/**
 * Cadence: the shader packs tested with Cadence (docs/SHADER-PACKS.md), so the pack list can say which ones are known to work
 * and roughly how heavy each is. Matched by name, ignoring case, spaces, dashes and underscores, so "BSL_v10.1.8.zip" and a
 * newer "BSL v10.2" both match "bsl". Weight is the GPU time per frame measured on an M2 MacBook Air (light under 12 ms,
 * heavy over 18 ms at the same settings); it only orders expectations, it isn't a promise.
 */
public final class PackCatalog {
	public enum Weight {
		LIGHT("Light", "fast on any Mac"),
		MEDIUM("Medium", "smooth on most Macs with Auto resolution"),
		HEAVY("Heavy", "slow on a MacBook Air; best on Pro and Max chips");

		public final String label, hint;

		Weight(String label, String hint) {
			this.label = label;
			this.hint = hint;
		}
	}

	public record Known(String key, Weight weight, String note) {}

	private static final List<Known> KNOWN = List.of(
		new Known("bsl", Weight.MEDIUM, ""),
		new Known("complementaryreimagined", Weight.MEDIUM, ""),
		new Known("complementaryunbound", Weight.MEDIUM, ""),
		new Known("makeup", Weight.MEDIUM, ""),
		new Known("sildursvibrant", Weight.MEDIUM, ""),
		new Known("sildursenhanceddefault", Weight.LIGHT, ""),
		new Known("ilikevanilla", Weight.LIGHT, ""),
		new Known("insanity", Weight.LIGHT, "a dark horror look, as intended"),
		new Known("vanillaex", Weight.LIGHT, ""),
		new Known("bliss", Weight.HEAVY, ""),
		new Known("pegasus", Weight.HEAVY, ""),
		// (tested 2026-10-18)
		new Known("photon", Weight.HEAVY, ""),
		new Known("solas", Weight.HEAVY, ""),
		new Known("kappa", Weight.HEAVY, ""),
		new Known("nostalgia", Weight.HEAVY, ""),
		new Known("astralex", Weight.HEAVY, ""),
		new Known("noble", Weight.HEAVY, "shaded sides darker than intended"),
		new Known("superdupervanilla", Weight.MEDIUM, ""),
		new Known("vanillaplus", Weight.MEDIUM, ""),
		new Known("potato", Weight.MEDIUM, ""),
		new Known("mellow", Weight.LIGHT, ""),
		new Known("miniature", Weight.LIGHT, ""));

	private PackCatalog() {
	}

	/** What Cadence knows about the pack with this file name, or null for a pack not tested yet. */
	public static Known lookup(String packName) {
		String n = squash(packName);
		for (Known k : KNOWN) if (n.startsWith(k.key)) return k;
		return null;
	}

	private static String squash(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
}
