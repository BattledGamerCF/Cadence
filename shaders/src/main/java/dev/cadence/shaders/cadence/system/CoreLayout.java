package dev.cadence.shaders.cadence.system;

import dev.cadence.metal.backend.MacSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cadence: the Mac's CPU core tiers, as macOS reports them (hw.nperflevels; hw.perflevelN.name / physicalcpu, fastest first).
 * Through the M4 there are two: Performance and Efficiency. From the M5 Apple calls the fastest cores "Super": the base M5 has
 * Super + Efficiency, the M5 Pro and Max Super + a new middle "Performance" tier, the M6 all three (2 + 4 + 6). Read once.
 */
public final class CoreLayout {
	public record Tier(String name, int cores) {}

	private static final List<Tier> TIERS = read();

	private CoreLayout() {
	}

	private static List<Tier> read() {
		List<Tier> out = new ArrayList<>();
		int levels = (int) MacSystem.sysLong("hw.nperflevels");
		for (int i = 0; i < Math.max(levels, 0); i++) {
			String name = MacSystem.sysString("hw.perflevel" + i + ".name");
			int cores = (int) MacSystem.sysLong("hw.perflevel" + i + ".physicalcpu");
			if (cores > 0) out.add(new Tier(name == null || name.isBlank() ? "Level " + i : name.strip(), cores));
		}
		return List.copyOf(out);
	}

	public static List<Tier> tiers() {
		return TIERS;
	}

	/** The fastest tier's cores (Super on M5 and later, Performance before). */
	public static int fastest() {
		return TIERS.isEmpty() ? 0 : TIERS.getFirst().cores();
	}

	/** The efficiency tier's cores (the slowest tier, when it is one), else 0. */
	public static int efficiency() {
		if (TIERS.size() < 2) return 0;
		Tier last = TIERS.getLast();
		return last.name().toLowerCase(Locale.ROOT).contains("effic") ? last.cores() : 0;
	}

	/**
	 * Whether there is a middle tier between the fastest cores and efficiency cores: three tiers (M6), or two where the second
	 * isn't Efficiency (M5 Pro / Max: Super + Performance). Bulk work then belongs there, leaving the few fastest cores to
	 * the threads that decide frame times.
	 */
	public static boolean hasMiddleTier() {
		// (two tiers count only when the second is named Performance: a name macOS might use that we haven't seen leaves the
		// workers as Java starts them, the behaviour before core tiers)
		return TIERS.size() >= 3 || TIERS.size() == 2 && TIERS.get(1).name().toLowerCase(Locale.ROOT).contains("perform")
			&& !TIERS.get(0).name().toLowerCase(Locale.ROOT).contains("perform");
	}

	public static String describe() {
		StringBuilder s = new StringBuilder();
		for (Tier t : TIERS) s.append(s.isEmpty() ? "" : " + ").append(t.cores()).append(' ').append(t.name());
		return s.isEmpty() ? "unknown" : s.toString();
	}
}
