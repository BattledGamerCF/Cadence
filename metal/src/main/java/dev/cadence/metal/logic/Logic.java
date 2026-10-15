package dev.cadence.metal.logic;

/**
 * Cadence Logic: game-logic (tick) optimizations, Cadence's own replacement for what Lithium does, written from scratch
 * (Lithium is LGPL-3.0; none of its code is used). Every optimization here gives exactly vanilla's results.
 * <ul>
 * <li>{@code cadence.logic=true} turns the set on; the performance profiles set it, so {@code profile=none} is pure vanilla.</li>
 * <li>{@code cadence.logic.<name>=false} turns one off (names: {@code entitySections}, {@code brain}).</li>
 * <li>Each steps aside when Lithium is installed: Lithium's own version then applies.</li>
 * </ul>
 */
public final class Logic {
	private Logic() {
	}

	/** Whether optimization {@code name} is on (read once, when its mixin is considered). */
	public static boolean enabled(String name) {
		if (!Boolean.parseBoolean(System.getProperty("cadence.logic", "false").strip())) return false;
		return !"false".equalsIgnoreCase(System.getProperty("cadence.logic." + name, "true").strip());
	}
}
