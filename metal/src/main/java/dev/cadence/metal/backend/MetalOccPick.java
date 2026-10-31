// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.backend;

import java.util.Arrays;

/**
 * Picks whichever of MetalTerrain's two exact paths is faster here: the split (occlusion) or Sodium's own draws. The split's
 * depth reload and hi-Z cost grow with the pixel count and shrink with memory bandwidth, what it saves grows with the scene,
 * so the crossover depends on the machine: on the M4 mini the split is 43% faster at 1920x1080 and 9-13% slower at
 * 3456x2234. A trial alternates the paths in blocks of BLOCK terrain frames and scores each by its median frame time (medians,
 * so a GC pause or chunk upload in one block can't decide it). The pick follows the sum of ln(split / plain) over every trial
 * at this size, not the latest one alone: which path wins changes with the view (one trial at 3456x2234 picked the split and
 * held it 8 s of a spin that plain won by 13%), and the sum weighs every view the trials saw. Trials run again when the
 * terrain pass changes size, which clears the sum, FIRST_RETRY after that and then at doubling gaps up to every RETRY.
 */
final class MetalOccPick {
	/** With 2 frames in flight a submit waits on the frame 2 before it, so a block's first SETTLE frames time the other path. */
	private static final int BLOCK = 24, SETTLE = 6, BLOCKS = 16, SAMPLES = BLOCKS / 2 * (BLOCK - SETTLE);
	private static final long FIRST_RETRY = 2_000_000_000L, RETRY = 30_000_000_000L;
	/** Frame times in ns: the split's, then plain's. */
	private final long[][] samples = new long[2][SAMPLES];
	/** Terrain frames into the running trial, -1 between trials. */
	private int frame = -1;
	private long lastSubmit, decidedAt, pixels, retry;
	/** Sum of ln(split / plain) over this size's trials: the split is picked while it is at most 0. */
	private double bias;
	private boolean split = true;

	/** Called at every submit with the pixels of that frame's terrain pass (0: it drew none); returns whether the next frame splits. */
	boolean next(long pixels) {
		long now = System.nanoTime(), time = now - this.lastSubmit;
		this.lastSubmit = now;
		if (pixels == 0) return this.split;
		boolean resized = pixels != this.pixels;
		if (resized || this.frame < 0 && now - this.decidedAt >= this.retry) {
			this.retry = resized ? FIRST_RETRY : Math.min(2 * this.retry, RETRY);
			if (resized) this.bias = 0;
			this.pixels = pixels;
			this.frame = 0;
			return this.split = true;
		}
		if (this.frame < 0) return this.split;
		int block = this.frame / BLOCK, at = this.frame % BLOCK - SETTLE;
		if (at >= 0) this.samples[block & 1][block / 2 * (BLOCK - SETTLE) + at] = time;
		if (++this.frame < BLOCKS * BLOCK) return this.split = (this.frame / BLOCK & 1) == 0;
		long split = median(this.samples[0]), plain = median(this.samples[1]);
		this.bias += Math.log((double) split / plain);
		this.split = this.bias <= 0;
		this.frame = -1;
		this.decidedAt = now;
		System.out.printf("cadence-metal occ: %d px, split %.3f ms, plain %.3f ms, sum %+.3f: %s%n", pixels, split / 1e6, plain / 1e6, this.bias,
			this.split ? "split" : "plain");
		return this.split;
	}

	private static long median(long[] times) {
		Arrays.sort(times);
		return times[times.length / 2];
	}
}
