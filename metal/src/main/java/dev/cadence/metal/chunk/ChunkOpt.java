// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.chunk;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in chunk-pipeline and server-tick changes. Every switch is a -Dcadence.chunk.NAME property; with none set no mixin
 * of this package applies (ChunkMixinPlugin), so the game is unchanged.
 *
 * <ul>
 * <li>{@code saveSkip=probe|true} (C2): a chunk write whose bytes equal what the region file already holds, apart from the
 *     LastUpdate stamp, is skipped ({@code true}); {@code probe} compares and counts but always writes. Its own Sodium-free
 *     package and mixin config: dev.cadence.metal.chunkio, cadence-metal-chunkio.mixins.json.</li>
 * <li>{@code mesh=air,bounds,vis,lambda} or {@code mesh=all} (C1, Sodium's meshers): empty neighbour sections share one AIR array
 *     instead of a 4096-entry fill (air); LevelSlice's volume check from three relative ranges in one branch (bounds); the
 *     section visibility graph by a bit-parallel sweep (vis); the two per-block method references of BlockRenderer.renderModel
 *     made once per renderer (lambda). Each gives the same mesh and visibility output as Sodium's code.</li>
 * <li>{@code clones=N} (C1, render thread): Sodium's cloned-section cache holds N sections instead of 512.</li>
 * <li>{@code clonesCleanup=true|verify|ab} (C1, render thread): the cache's per-frame expiry walk stops at the first entry that
 *     has not expired (the entries are in last-use order), so it costs what it evicts; verify checks nothing expired is left,
 *     ab times the original and this walk on random frames of the same run.</li>
 * <li>{@code parse=true|verify} (C4): chunk-section palettes decoded without DFU (FastPalette); verify decodes both ways, keeps
 *     vanilla's result and counts mismatches.</li>
 * <li>{@code serialize=true|verify} (C2/C4): chunk-section palettes encoded for saving without DFU; verify encodes both
 *     ways and compares the serialized bytes.</li>
 * <li>{@code poi=true|verify} (D1): AcquirePoi's POI search as loops (PoiScan), same results and side effects in the same
 *     order; verify runs vanilla for real and checks the loops against it on every call.</li>
 * <li>{@code ticks=true} (D3): ServerLevel.tickChunk without per-pick overhead, same random ticks in the same order.</li>
 * <li>{@code chunkGet=true} (D2/D5): a chunk's section index offset computed once instead of per block access.</li>
 * <li>{@code light=true|verify} (C3): light section maps published as base + delta snapshots instead of full clones
 *     (LightSnapshots); verify checks every snapshot against vanilla's clone. Off (one log line) when -Dcadence.alloc.lightMap is
 *     on: that one makes the same maps copy-on-write, and the two can't run together.</li>
 * <li>{@code stats=true}: count what the switches did (bench reports read {@link #snapshot()}).</li>
 * </ul>
 */
public final class ChunkOpt {
	private ChunkOpt() { }

	public static final String SAVE_SKIP = dev.cadence.metal.chunkio.ChunkIo.SAVE_SKIP; // saveSkip lives in dev.cadence.metal.chunkio (no Sodium)
	public static final boolean SAVE_SKIP_ON = dev.cadence.metal.chunkio.ChunkIo.SAVE_SKIP_ON;
	public static final boolean SAVE_PROBE = dev.cadence.metal.chunkio.ChunkIo.SAVE_PROBE;
	public static final java.util.Set<String> MESH = meshSet(prop("mesh"));
	public static final int CLONES = Integer.getInteger("cadence.chunk.clones", 0);
	public static final boolean CLONES_VERIFY = Boolean.getBoolean("cadence.chunk.clonesVerify");
	public static final String CLONES_CLEANUP = prop("clonesCleanup");
	public static final boolean CLONES_CLEANUP_VERIFY = "verify".equals(CLONES_CLEANUP);
	public static final boolean CLONES_CLEANUP_AB = "ab".equals(CLONES_CLEANUP);
	public static final boolean CLONES_CLEANUP_ON = "true".equals(CLONES_CLEANUP) || CLONES_CLEANUP_VERIFY || CLONES_CLEANUP_AB;
	public static final String PARSE = prop("parse");
	public static final boolean PARSE_VERIFY = "verify".equals(PARSE);
	public static final boolean PARSE_FAST = "true".equals(PARSE) || PARSE_VERIFY;
	public static final boolean SERIALIZE_VERIFY = "verify".equals(prop("serialize"));
	public static final boolean SERIALIZE = "true".equals(prop("serialize")) || SERIALIZE_VERIFY;
	public static final boolean POI_VERIFY = "verify".equals(prop("poi"));
	public static final boolean POI = "true".equals(prop("poi")) || POI_VERIFY;
	public static final boolean TICKS = Boolean.getBoolean("cadence.chunk.ticks");
	public static final boolean CHUNK_GET = Boolean.getBoolean("cadence.chunk.chunkGet");
	/**
	 * -Dcadence.alloc.lightMap makes the light engine's section map copy-on-write; light (below) replaces the same
	 * snapshots with base + delta maps. The two can't run together (the integrated server crashed at world load), so with
	 * alloc.lightMap on, light switches itself off and says so once.
	 */
	public static final boolean ALLOC_LIGHT_MAP = Boolean.getBoolean("cadence.alloc.lightMap");
	private static final boolean LIGHT_REQUESTED = "true".equals(prop("light")) || "verify".equals(prop("light"));
	public static final boolean LIGHT_VERIFY = "verify".equals(prop("light")) && !ALLOC_LIGHT_MAP;
	public static final boolean LIGHT = LIGHT_REQUESTED && !ALLOC_LIGHT_MAP;
	public static final boolean STATS = Boolean.getBoolean("cadence.chunk.stats") || SAVE_PROBE || PARSE_VERIFY || POI_VERIFY || CLONES_VERIFY || LIGHT_VERIFY || SERIALIZE_VERIFY
		|| CLONES_CLEANUP_VERIFY || CLONES_CLEANUP_AB;

	static {
		if (LIGHT_REQUESTED && ALLOC_LIGHT_MAP) {
			System.out.println("cadence-chunk: light off: cadence.alloc.lightMap is on (both replace the light engine's section-map copies; they can't run together)");
		}
	}

	private static java.util.Set<String> meshSet(String v) {
		java.util.Set<String> out = new java.util.TreeSet<>();
		for (String s : v.split(",")) if (!s.isBlank()) out.add(s.trim());
		if (out.remove("all")) out.addAll(java.util.List.of("air", "bounds", "vis", "lambda"));
		return java.util.Collections.unmodifiableSet(out);
	}

	public static boolean mesh(String feature) {
		return MESH.contains(feature);
	}

	private static String prop(String name) {
		String v = System.getProperty("cadence.chunk." + name);
		return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
	}

	/** Whether the mixin named (simple class name) should apply: each one belongs to exactly one switch. */
	public static boolean mixinEnabled(String simpleName) {
		return switch (simpleName) {
			case "LevelSliceAirMixin" -> mesh("air");
			case "LevelSliceBoundsMixin" -> mesh("bounds");
			case "DirectionalVisGraphMixin", "BitArrayAccessor" -> mesh("vis");
			case "BlockRendererMixin" -> mesh("lambda");
			case "LevelBiomeSliceMixin" -> mesh("biome");
			case "ClonedChunkSectionCacheMixin" -> CLONES > 0;
			case "ClonedChunkSectionCacheCleanupMixin" -> CLONES_CLEANUP_ON;
			case "PalettedContainerFactoryMixin" -> PARSE_FAST || SERIALIZE;
			case "AcquirePoiMixin", "PoiSectionAccessor", "SectionStorageAccess" -> POI;
			case "ServerLevelTickMixin", "LevelRandAccessor" -> TICKS;
			case "ChunkAccessSectionMixin" -> CHUNK_GET;
			case "DataLayerStorageMapMixin", "BlockLightMapMixin", "SkyLightMapMixin" -> LIGHT;
			default -> false;
		};
	}


	static {
		// -Dcadence.chunk.statsLog=true: the counters on stdout when the JVM exits (startup-only runs have no per-phase report).
		if (STATS && Boolean.getBoolean("cadence.chunk.statsLog")) {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("cadence-chunk: stats " + snapshot()), "cadence-chunk stats"));
		}
	}

	public static void count(String key, long n) {
		dev.cadence.metal.chunkio.ChunkIo.count(key, n); // one set of counters for every chunk switch
	}

	public static void count(String key) {
		count(key, 1);
	}

	/** Cumulative counters since start, sorted by name (the bench stores one per phase and diffs them). */
	public static Map<String, Object> snapshot() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("flags", Map.of("saveSkip", SAVE_SKIP, "mesh", String.join(",", MESH), "clones", CLONES, "parse", PARSE, "clonesCleanup", CLONES_CLEANUP,
			"light", LIGHT_VERIFY ? "verify" : LIGHT ? "true" : LIGHT_REQUESTED ? "off (alloc.lightMap)" : ""));
		out.putAll(dev.cadence.metal.chunkio.ChunkIo.counters());
		return out;
	}
}
