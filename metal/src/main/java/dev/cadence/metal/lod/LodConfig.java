// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.lod;

/**
 * Far terrain switches, all read once at startup. Off unless -Dcadence.lod=true (or any -Dcadence.lod.radius): then nothing of
 * this package loads and the mixins that call into it are rejected by LodMixinPlugin.
 */
public final class LodConfig {
	/** -Dcadence.lod=true: far terrain on. A radius alone turns it on too. */
	public static final boolean ENABLED = Boolean.getBoolean("cadence.lod") || System.getProperty("cadence.lod.radius") != null;
	/**
	 * -Dcadence.lod.small=true: far terrain for small GPUs (a MacBook Neo's 5 cores). Its cost there is
	 * geometry (the GPU cull and its survivors' quads), not pixels, so this mode culls and draws fewer cells: a quarter-size
	 * clipmap window (lod.n 512: level 0 to ~190 blocks, inside a 16-chunk render distance, so the far terrain starts with
	 * 2-block cells), with crowns floating over their ground on level 1 too (crownLevels 2); the hand-off's depth push
	 * (lod.handoffPush 0.9995), out-of-view chunks handed off once built (lod.handoffUnseen), and a real chunk's rewrite of
	 * the coarser levels held while it's in view and not handed off (lod.chunkHold). Each can still be set
	 * on its own; an explicit flag wins.
	 */
	public static final boolean SMALL = Boolean.getBoolean("cadence.lod.small");
	/** -Dcadence.lod.radius=N: how far the far terrain reaches, in chunks (to the edge of the coarsest tiles drawn). */
	public static final int RADIUS_CHUNKS = Integer.getInteger("cadence.lod.radius", 512);
	/**
	 * -Dcadence.lod.n=N: cells across each clipmap level's window (a power of two). Level L's cells are 2^L blocks and it is drawn
	 * out to (N / 2 - 64) x 2^L blocks, so on screen a cell is never more than about twice what a real block at that distance
	 * would be, and N sets how big that is: at 5K, 2048 gives cells of 2.5-5 px (level 0 to ~960 blocks), 4096 1.2-2.5 px.
	 * Memory: 8 N^2 bytes a level.
	 */
	public static final int N = Integer.getInteger("cadence.lod.n", SMALL ? 512 : 2048);
	/** -Dcadence.lod.threads=N: generation workers (default: the cores the game and the GPU driver leave). */
	public static final int THREADS = Integer.getInteger("cadence.lod.threads", Math.max(2, Runtime.getRuntime().availableProcessors() - 4));
	/**
	 * -Dcadence.lod.crownLevels=N: the N finest levels draw tree crowns floating over their ground (the terrain behind shows
	 * under them), 0: crowns stand on the ground like pillars. Costs 4 bytes a cell on those levels. Default 1 (level 0, where
	 * trees are several pixels).
	 */
	public static final int CROWN_LEVELS = Integer.getInteger("cadence.lod.crownLevels", SMALL ? 2 : 1);
	/**
	 * -Dcadence.lod.walk=segmented|serial: the column walk as one thread per column walking every level (serial), or a thread
	 * per level of each column in two passes (segmented: more threads, a GPU this wide stays busy). Same picture.
	 */
	public static final boolean SEGMENTED = "segmented".equals(System.getProperty("cadence.lod.walk", "serial"));
	/** -Dcadence.lod.walk=rows: -Dcadence.lod.rowSegs (48) threads per column, each a segment of its rows of equal expected work (lod_rows). */
	public static final boolean ROWS = "rows".equals(System.getProperty("cadence.lod.walk", "serial"));
	public static final int ROW_SEGS = Math.max(1, Integer.getInteger("cadence.lod.rowSegs", 48));
	/**
	 * -Dcadence.lod.nearDetail=false: no per-pixel detail on level 0 (the game's smooth-lighting occlusion from the neighbor
	 * columns, the grass side's band, the material under the top block on deeper walls).
	 */
	public static final boolean NEAR_DETAIL = Boolean.parseBoolean(System.getProperty("cadence.lod.nearDetail", "true"));
	/**
	 * -Dcadence.lod.textures=false: level 0 drawn with flat colors per face instead of the blocks' own textures (at the mip the
	 * distance calls for, so they fade into their average color as the blocks shrink to a pixel).
	 */
	public static final boolean TEXTURES = Boolean.parseBoolean(System.getProperty("cadence.lod.textures", "true"));
	/**
	 * -Dcadence.lod.plants=false: no grass, ferns or flowers on level 0. With them (needs textures), the game's own plant features
	 * run with its trees, and the walk draws each plant's crossed quads where the real chunks will have them.
	 */
	public static final boolean PLANTS = TEXTURES && Boolean.parseBoolean(System.getProperty("cadence.lod.plants", "true"));
	/**
	 * -Dcadence.lod.treeLevels=N: the N finest levels (at most 2) get the game's own trees (its tree features run on the far
	 * terrain); coarser levels the biome's impostor canopy. Level 1 needs every chunk's block-exact ground: ~5x the work of
	 * its tiles. Default 1.
	 */
	public static final int TREE_LEVELS = Integer.getInteger("cadence.lod.treeLevels", 1);
	/** -Dcadence.lod.crownShade=F: sky light factor under tree crowns (the game's sky light drops under leaves). */
	public static final double CROWN_SHADE = Double.parseDouble(System.getProperty("cadence.lod.crownShade", "0.7"));
	/**
	 * -Dcadence.lod.fineDensity=false: levels 0-1 from the terrain shape without caves (as the coarser levels) instead of the game's
	 * final density, where cave mouths and pits break the surface as they do in the real chunks.
	 */
	public static final boolean FINE_DENSITY = Boolean.parseBoolean(System.getProperty("cadence.lod.fineDensity", "true"));
	/** -Dcadence.lod.fineLevels=N: the N finest levels take the final density (with fineDensity); default 2 (levels 0-1). */
	public static final int FINE_LEVELS = Integer.getInteger("cadence.lod.fineLevels", 2);
	/**
	 * -Dcadence.lod.verify=true: every real chunk that arrives over generated far terrain is compared with it column by column
	 * first (surface, trees, plants, blocks), the agreement logged as it accumulates (cadence-lod: verify).
	 */
	public static final boolean VERIFY = Boolean.getBoolean("cadence.lod.verify");
	/** -Dcadence.lod.cache=false: no disk cache (every tile generated again each session). */
	public static final boolean DISK_CACHE = Boolean.parseBoolean(System.getProperty("cadence.lod.cache", "true"));
	/** -Dcadence.lod.cacheDir=PATH: where the per-world cache lives (default: the game dir's cadence-lod). */
	public static final String CACHE_DIR = System.getProperty("cadence.lod.cacheDir");
	/** -Dcadence.lod.trees=false: no tree canopy on generated (never visited) terrain. */
	public static final boolean TREES = Boolean.parseBoolean(System.getProperty("cadence.lod.trees", "true"));
	/**
	 * -Dcadence.lod.fogStart=F: the render-distance fade starts at F x the reach. Default (unset): the game's own, over the last
	 * tenth of the reach (4 to 64 blocks).
	 */
	public static final double FOG_START = Double.parseDouble(System.getProperty("cadence.lod.fogStart", "-1"));
	/**
	 * -Dcadence.lod.haze=H: the game's environmental (linear) fog range is stretched to H x the reach when that is longer than
	 * the game's 1024 blocks: far terrain fades into the fog color with distance. Default 1.
	 */
	public static final double HAZE = Double.parseDouble(System.getProperty("cadence.lod.haze", "1.0"));
	/**
	 * -Dcadence.lod.render=walk|mesh: the far terrain drawn by the column walk (a compute pass per screen column over the
	 * clipmap, composited in the level pass) or rasterized from per-tile meshes of the same cells (LodMesh: a GPU cull, then
	 * indirect draws shaded by the same code). Same picture.
	 */
	public static final boolean MESH = "mesh".equals(System.getProperty("cadence.lod.render", "walk"));
	/** -Dcadence.lod.stats=true: once a second, what was drawn and generated. */
	public static final boolean STATS = Boolean.getBoolean("cadence.lod.stats");
	/** -Dcadence.lod.draw=false: generate, but draw nothing (prices the drawing alone). */
	public static final boolean DRAW = Boolean.parseBoolean(System.getProperty("cadence.lod.draw", "true"));
	/** -Dcadence.lod.dump=DIR, -Dcadence.lod.dumpFrame=N: raw dump of frame N's walk inputs for the offline harness. */
	public static final String DUMP = System.getProperty("cadence.lod.dump");
	public static final long DUMP_FRAME = Long.getLong("cadence.lod.dumpFrame", 600);
	/** -Dcadence.lod.chunks=false: never summarize the game's own chunks (generated terrain only). */
	public static final boolean CHUNKS = Boolean.parseBoolean(System.getProperty("cadence.lod.chunks", "true"));

	/**
	 * -Dcadence.lod.probe=MS: an in-run A/B of the draw: it alternates on and off every MS milliseconds and the mean frame
	 * interval of each state is logged every few seconds ("cadence-lod probe"), so its marginal cost survives background drift.
	 */
	public static final int PROBE_MS = Integer.getInteger("cadence.lod.probe", 0);
	private LodConfig() {
	}

	/** The reach in blocks. */
	public static double reachBlocks() {
		return RADIUS_CHUNKS * 16.0;
	}
}
