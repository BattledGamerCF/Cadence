// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import java.util.HashMap;
import java.util.Map;

/**
 * In-run A/B of candidate flags: with -Dcadence.own.probe=MS, a probe mode
 * (an entry of -Dcadence.own.probeModes, '/'-separated) may be a set of flag overrides, "key=value" pairs joined by '+', keys without
 * the "cadence.own." prefix (e.g. "frag.splitNear=192+frag.testLate=false"); "base" is the build as configured. A flag on the indie
 * path takes part by reading itself through {@link #bool} / {@link #integer} with its static final as the default: without the probe
 * (ON false, a constant) that is the static final itself, so the default path doesn't change.
 * <p>
 * Registered (switchable at runtime): vignetteNoop (verified black-source HUD vignette only), frag.splitNear (positive values, with a positive static), frag.testLate, frag.msub, frag.fine,
 * frag.testFast, frag.testGather, frag.bRedraw, hizTop, hizGather, frag.pyrSkip (0-2; the pyramid is reallocated when its padding changes),
 * frag.a1Exact (A1's units as exact-count draws, OwnFrag.A1_EXACT), frag.a2Exact (A2's, OwnFrag.A2_EXACT),
 * frag.uocc (false: the frame draws the flags' set without uocc, as -Dcadence.own.frag.uoccMaxMp does above its size), frag.fogVert (our
 * translucent draw's exact per-vertex fog); measurement only: frag.dropSolidA (OwnFrag.DROP_SOLID_A), frag.aKind (phase A's first solid draw, OwnFrag.aKind), frag.tKind (the
 * translucent draw). To register another: read it through bool / integer at the point it's used each
 * frame, and only if nothing sized or built once depends on it.
 */
public final class OwnProbe {
	/** The in-run probe is on (OwnTerrain switches modes every this many ms). */
	static final boolean ON = Long.getLong("cadence.own.probe", 0) > 0;
	private static volatile Map<String, String> overrides = Map.of();

	private OwnProbe() {
	}

	/** This frame's overrides from probe mode m: true if m is a flag-override mode or "base" (the rest of the probe treats it as "draw"). */
	static boolean select(String m) {
		if (m.equals("base")) {
			overrides = Map.of();
			return true;
		}
		if (!m.contains("=")) {
			overrides = Map.of();
			return false;
		}
		Map<String, String> o = new HashMap<>();
		for (String kv : m.split("\\+")) {
			int i = kv.indexOf('=');
			if (i > 0) o.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
		}
		overrides = o;
		return true;
	}

	/** Warm mailbox accepts only audited runtime reads, never arbitrary system properties or draw diagnostics. */
	public static void configureWarm(String modes, long milliseconds) {
		if (!Boolean.getBoolean("cadence.bench.warm") || !ON)
			throw new IllegalStateException("warm requires cadence.bench.warm=true and cadence.own.probe>0");
		String[] names = modes.split("/", -1);
		if (names.length < 1 || names.length > 8 || milliseconds < 100 || milliseconds > 5000)
			throw new IllegalArgumentException("1..8 modes, 100..5000 ms required");
		for (String name : names) {
			if (name.equals("base")) continue;
			for (String pair : name.split("\\+", -1)) {
				String[] kv = pair.split("=", -1);
				if (kv.length != 2) throw new IllegalArgumentException("expected key=value: " + pair);
				switch (kv[0]) {
					case "frag.splitNear" -> { if (OwnFrag.SPLIT_NEAR <= 0) throw new IllegalArgumentException("splitNear needs positive startup split/banks"); int n = Integer.parseInt(kv[1]); if (n < 1 || n > 4096) throw new IllegalArgumentException("splitNear 1..4096"); }
					case "frag.pyrSkip" -> { int n = Integer.parseInt(kv[1]); if (n < 0 || n > 2) throw new IllegalArgumentException("pyrSkip 0..2"); }
					case "frag.fine" -> { if (!java.util.Set.of("1", "2", "4").contains(kv[1])) throw new IllegalArgumentException("fine 1/2/4"); }
					case "frag.testLate", "frag.msub", "frag.testFast", "frag.testGather", "hizTop", "hizGather", "frag.uocc", "frag.fogVert" -> {
						if (!kv[1].equals("true") && !kv[1].equals("false")) throw new IllegalArgumentException("boolean required");
					}
					default -> throw new IllegalArgumentException("not a warm-safe probe key: " + kv[0]);
				}
			}
		}
		OwnTerrain.configureWarm(names, milliseconds);
	}

	public static boolean bool(String key, boolean dflt) {
		if (!ON) return dflt;
		String v = overrides.get(key);
		return v == null ? dflt : Boolean.parseBoolean(v);
	}

	static String string(String key, String dflt) {
		if (!ON) return dflt;
		String v = overrides.get(key);
		return v == null ? dflt : v;
	}

	static int integer(String key, int dflt) {
		if (!ON) return dflt;
		String v = overrides.get(key);
		return v == null ? dflt : Integer.parseInt(v);
	}
}
