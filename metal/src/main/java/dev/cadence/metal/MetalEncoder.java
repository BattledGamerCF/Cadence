// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * One MTLCommandBuffer per submit (per frame). Metal tracks hazards on its own, so unlike the Vulkan backend there
 * are no barriers: copies, clears and passes are simply recorded in order.
 */
final class MetalEncoder implements CommandEncoderBackend {
	/**
	 * Same pacing as the Vulkan backend: submitting frame N waits for frame N-2. -Dcadence.metal.inFlight=0 waits for each frame
	 * itself, so no two frames' GPU work overlaps and a GPU trace times each encoder alone (slow; don't bench it).
	 */
	private static final int MAX_IN_FLIGHT = Integer.getInteger("cadence.metal.inFlight", 2);
	/**
	 * Render Max FPS (MetalBridge.uncappedPresent): frames in flight while it's on. Its frames are tiny (a clear and two lines of
	 * text), so the GPU round trip of each, not its work, set the pace: 2 in flight reached ~9,100 fps on an M2, 4 ~12,000.
	 * -Dcadence.metal.maxFpsInFlight. The rings frames share are fenced, not counted, so any depth is safe.
	 */
	private static final int MAX_FPS_IN_FLIGHT = Integer.getInteger("cadence.metal.maxFpsInFlight", 5);
	static volatile boolean maxFps;
	/** Record Mode: frames in flight while it's chosen (its frames are compute only; the GPU's per-frame latency is the limit). */
	private static final int RECORD_IN_FLIGHT = Integer.getInteger("cadence.record.inFlight", 16);
	static volatile boolean record;
	/**
	 * Record Mode: its frames committed recordBatch to a command buffer (-Dcadence.record.batch, 1: each its own). Each frame is
	 * still its own dispatch rendering its own image; a command buffer costs the M2 ~20 us of its own (tools/record_bench.m
	 * RB_BATCH), which at 20k frames a second is ~40% of a frame. A frame that presents commits at once.
	 */
	static volatile int recordBatch = Math.max(1, Integer.getInteger("cadence.record.batch", 4));
	/** Set by Record when this frame was a record frame (only those are batched). */
	public static boolean recordFrame;
	private int recordBatched;
	/**
	 * -Dcadence.metal.trace=N prints every encoder operation of submit N, the tool for finding pass boundaries worth removing,
	 * and the average per-encoder GPU time of submits N to N + PROFILE_FRAMES - 1. Traced runs are a few % slower; don't bench them.
	 */
	private static volatile long TRACE_SUBMIT = "later".equals(System.getProperty("cadence.metal.trace")) ? Long.MAX_VALUE - 1000
		: Long.getLong("cadence.metal.trace", -1);
	/** The submit being recorded (for traceSoon). */
	private static volatile long lastSubmit;

	/** -Dcadence.metal.trace=later: the trace is taken of the submit a moment from now (the self-test's "gputrace" step). */
	static void traceSoon() {
		if (TRACE_SUBMIT >= 0) TRACE_SUBMIT = lastSubmit + 30;
	}
	/** -Dcadence.own.int.encLog (measurement only, with frameLog): GPU timestamps on every submit, each encoder logged (FrameLog's enclog.csv). */
	private static final boolean ENC_LOG = Boolean.getBoolean("cadence.own.int.encLog");
	/** -Dcadence.metal.passTest=N,MODE (measurement only, with encLog): N empty passes at the head of every 20th frame (mcmetal.m mc_pass_test). */
	private static final int PASS_TEST_N, PASS_TEST_MODE;
	static {
		String[] pt = System.getProperty("cadence.metal.passTest", "0,0").split(",");
		PASS_TEST_N = Integer.parseInt(pt[0].trim());
		PASS_TEST_MODE = pt.length > 1 ? Integer.parseInt(pt[1].trim()) : 0;
	}
	private boolean passTestPending;
	private String[] encLabels = new String[512];
	private long[][] encStats = new long[512][4];  // per group: draws, texture binds, first binds this submit, cold binds
	private int encGroupOpen = -1;

	boolean encLogOn() {
		return ENC_LOG;
	}

	void encBind(MetalTexture t) {
		if (this.encGroupOpen < 0 || this.encGroupOpen >= 128) return;
		long[] st = this.encStats[this.encGroupOpen];
		st[1]++;
		if (t.encLastBind != this.submitIndex) st[2]++;
		if (t.encLastBind < this.submitIndex - 30) st[3]++;
		t.encLastBind = this.submitIndex;
	}
	/** Above this, an upload is cheaper as an async GPU blit than as a memcpy on the render thread. */
	private static final long CPU_COPY_MAX = 256 << 10;
	/** Measurement: -Dcadence.metal.uploadStats=true prints every 10 s how many buffer-write bytes went straight into the buffer vs through a staging copy and a GPU blit. */
	private static final boolean UPLOAD_STATS = Boolean.getBoolean("cadence.metal.uploadStats");
	private static long directBytes, stagedBytes, directWrites, stagedWrites, stagedArena, statsAt;

	private static void uploadStat(boolean direct, long bytes, boolean arena) {
		if (direct) { directBytes += bytes; directWrites++; } else { stagedBytes += bytes; stagedWrites++; if (arena) stagedArena += bytes; }
		long now = System.nanoTime();
		if (statsAt == 0) statsAt = now;
		if (now - statsAt < 10_000_000_000L) return;
		statsAt = now;
		System.out.printf(java.util.Locale.ROOT, "[cadence] uploads 10s: direct %d writes %.1f MB, staged %d writes %.1f MB (arena %.1f MB)%n",
			directWrites, directBytes / 1048576.0, stagedWrites, stagedBytes / 1048576.0, stagedArena / 1048576.0);
		directBytes = stagedBytes = directWrites = stagedWrites = stagedArena = 0;
	}
	private static final int PROFILE_FRAMES = 100, PROFILE_ENCODERS = 256;
	private static final boolean GPU_TIMES = Boolean.getBoolean("cadence.metal.gpuTimes");
	/**
	 * -Dcadence.metal.presentQueue=true (idea: ByteV0rtex, noahdunnagan/mcopt#2): present from a second command queue, so the frame's
	 * command buffer, which the render thread waits on, never touches the drawable (mc_present_queued in mcmetal.m).
	 */
	static final boolean PRESENT_QUEUE = "true".equals(System.getProperty("cadence.metal.presentQueue")) || "acquire".equals(System.getProperty("cadence.metal.presentQueue"));
	/** -Dcadence.metal.presentQueue=acquire: also acquire the drawable on the present side, so the render thread never waits in nextDrawable. */
	static final boolean PRESENT_ACQUIRE = "acquire".equals(System.getProperty("cadence.metal.presentQueue"));
	private static final boolean STATS = Boolean.getBoolean("cadence.metal.stats");

	final long ctx;
	final long enc;
	final MetalTransientMemory transientMemory;
	/** Draws Sodium's solid and cutout terrain with occlusion on (-Dcadence.metal.occ=true); see MetalRenderPass.recording. */
	final MetalTerrain terrain;
	private final ArrayDeque<Frame> inFlight = new ArrayDeque<>();
	private List<Runnable> afterThisFrame = new ArrayList<>();
	private final Set<MetalTexture> pendingClears = new HashSet<>();
	/**
	 * -Dcadence.metal.lazyClears: a deferred clear is no longer written out as an empty pass whenever some other pass opens (all pending
	 * clears that weren't the new pass's attachments were, in case the pass samples them); it stays pending until the texture is
	 * drawn to (folded into that pass's load action) or actually bound for sampling. The second case splits the open pass: end it, write
	 * the clear, reopen the same attachments with load, restore the pass's state. Why: the game clears the main target, then updates
	 * the lightmap (on ticks) or draws other small passes before the level; the old flush turned the main target's clear into two
	 * full-screen passes, which wait for the previous frame (still drawing that target) and hold up everything after them, so the
	 * level's main pass loses its overlap with the previous frame (~0.6-1 ms of GPU on the base chips on those frames). Exact: every
	 * texture still reads its clear wherever it is read.
	 */
	// Cadence: on by default (packs: ~1.6-1.75 ms less GPU a frame with BSL; no pack: -0.07 ms at full resolution, -0.31 ms with
	// World Resolution below 100%, where the window-size target's clear was paid and then drawn over). -Dcadence.metal.lazyClears=false: off.
	static volatile boolean LAZY_CLEARS = !"false".equals(System.getProperty("cadence.metal.lazyClears"));
	static final boolean CLEAR_ALL_MIPS = Boolean.getBoolean("cadence.metal.clearAllMips");
	private long[] passColors = new long[8];
	private int passColorCount, passWidth, passHeight;
	private long passDepth;
	private boolean passLazy;
	private long lazySplits, lazySplitLog;
	private long submitIndex;
	private long completedIndex = -1;
	/** Render encoders opened in this submit; the trace's #numbers, which the GPU profile lines refer to. */
	private int encoderIndex;
	private @Nullable MetalRenderPass currentPass;
	/** Recording the current submit's terrain draws for the visibility probe (-Dcadence.metal.probe=N); null otherwise. */
	/** Summed GPU µs over the profiled frames: whole submit, then vertex and fragment per encoder. */
	private final double[] profileSums = new double[1 + 2 * PROFILE_ENCODERS];
	private String[] traceLabels;
	private int profileShape = -1, profileSkipped;
	/** Per encoder: summed {vertex start, vertex end, fragment start, fragment end} offsets in the frame (us from its first sample), and how many frames had each. */
	private final double[] profileAt = new double[4 * PROFILE_ENCODERS];
	private final int[] profileAtN = new int[4 * PROFILE_ENCODERS];
	private int profiledFrames;
	private long waitNanos, statStart, statTerrainFrames;
	private int statFrames, statPresents;

	private record Frame(long index, long cmd, List<Runnable> after) {
	}

	MetalEncoder(long ctx) {
		this.ctx = ctx;
		this.enc = Native.encNew(ctx);
		if (GPU_TIMES) GpuTimes.initialize();
		this.transientMemory = new MetalTransientMemory(ctx, this);
		this.terrain = new MetalTerrain(this);
	}

	boolean tracing() {
		return this.submitIndex == TRACE_SUBMIT;
	}

	/**
	 * -Dcadence.metal.frameDepthDiscard: at a submit that presented (the frame's end), the main render target's depth is not written back
	 * (its open encoder stores it 'dontCare'). Vanilla clears the main color and depth unconditionally at the start of every frame
	 * (GameRenderer.render: clearColorAndDepthTextures before anything draws) and clears the depth again before the GUI, so nothing
	 * reads the depth a frame ends with: 8 MB a frame at 1080p, 33 MB at 4K of store traffic at the frame's tail.
	 */

	private boolean presentedThisSubmit;

	void trace(String op, Object... args) {
		if (!this.tracing()) return;
		StringBuilder line = new StringBuilder("cadence-metal trace: ").append(op);
		for (Object a : args) line.append(' ').append(a instanceof GpuTexture t ? t.getLabel() : a instanceof GpuTextureView v ? v.texture().getLabel() : a);
		System.out.println(line);
	}

	/** Marks a buffer as referenced by the submit being recorded. Every GPU reference to a buffer must go through here. */
	MetalBuffer use(GpuBuffer buffer) {
		MetalBuffer b = (MetalBuffer) buffer;
		b.lastUse = this.submitIndex;
		return b;
	}

	/** True when no recorded or in-flight GPU work touches b, so the CPU may read and write it right now. */
	private boolean idle(MetalBuffer b) {
		return b.lastUse <= this.completedIndex;
	}

	void releaseLater(long handle) {
		this.afterThisFrame.add(() -> Native.release(handle));
	}

	void afterGpuFinishes(Runnable r) {
		this.afterThisFrame.add(r);
	}

	@Override
	public void submit() {
		if (this.currentPass != null) throw new IllegalStateException("Cannot submit inside a render pass");
		boolean recordOnly = recordFrame;
		recordFrame = false;
		if (recordOnly && !this.presentedThisSubmit && !profiled(this.submitIndex) && ++this.recordBatched < recordBatch) return;
		this.recordBatched = 0;
		this.trace("submit");
		this.transientMemory.endSubmit();
		long samples = 0;
		int sampleCount = 0;
		if (profiled(this.submitIndex)) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long count = stack.ncalloc(4, 1, 4);
				if (ENC_LOG || TRACE_SUBMIT >= 0) {
					long names = stack.ncalloc(1, 512 * 64, 1);
					Native.profileLabels(this.enc, names, 64, 512);
					for (int g = 0; g < 512; g++) {
						String native_ = MemoryUtil.memASCII(names + g * 64L);
						if (native_.isEmpty()) continue;
						// native kind and label; the game's pass label where the native one is empty
						String java = this.encLabels[g];
						this.encLabels[g] = java == null ? native_ : native_.length() > 2 ? native_ + " {" + java + "}" : native_ + java;
						long[] st = this.encStats[g];
						if (st[0] + st[1] > 0) this.encLabels[g] += " d=" + st[0] + " tb=" + st[1] + " first=" + st[2] + " cold=" + st[3];
					}
					// the traced submit's labels name the per-encoder GPU times printed after PROFILE_FRAMES submits
					if (this.submitIndex == TRACE_SUBMIT) this.traceLabels = this.encLabels.clone();
				}
				samples = Native.profileEnd(this.enc, count);
				sampleCount = MemoryUtil.memGetInt(count);
			}
		}
		this.terrain.releaseHeld(true);
		if (this.presentedThisSubmit && dev.cadence.metal.own.OwnProbe.bool("metal.frameDepthDiscard", FrameHints.depthDiscard)) {  // (in-run A/B: probe mode metal.frameDepthDiscard=true)
			net.minecraft.client.renderer.GameRenderer game = net.minecraft.client.Minecraft.getInstance().gameRenderer;
			GpuTexture depth = game != null ? game.mainRenderTarget().getDepthTexture() : null;
			if (depth instanceof MetalTexture t) Native.discard(this.enc, t.handle);  // (no-op unless it is the open encoder's depth)
			var extra = FrameHints.extraDepth;
			GpuTexture more = extra != null ? extra.get() : null;
			if (more instanceof MetalTexture t) Native.discard(this.enc, t.handle);
		}
		this.presentedThisSubmit = false;
		MetalEvents.Operation commitEvent = MetalEvents.begin("commit", this.submitIndex, 0);
		long cmd;
		try {
			if (GPU_TIMES) GpuTimes.submit(this.submitIndex, this.encoderIndex);
			cmd = Native.encCommit(this.enc);
		} finally {
			MetalEvents.end(commitEvent);
		}
		this.terrain.endFrame();
		if (samples != 0) {
			long s = samples;
			int n = sampleCount;
			if (ENC_LOG) {
				long submit = this.submitIndex;
				String[] labels = this.encLabels;
				this.afterThisFrame.add(() -> FrameLog.encoders(this.ctx, submit, s, n, labels));
			} else {
				this.afterThisFrame.add(() -> this.accumulateProfile(cmd, s, n));
			}
		}
		this.inFlight.add(new Frame(this.submitIndex++, cmd, this.afterThisFrame));
		lastSubmit = this.submitIndex;
		if (TRACE_SUBMIT >= 0) Native.rpLog(this.submitIndex == TRACE_SUBMIT);  // (measurement: the traced submit's render encoders, mcmetal.m rpLog)
		if (dev.cadence.metal.cpu.Cpu.FENCE_STATS) dev.cadence.metal.cpu.Cpu.fenceTick(this.submitIndex); // opt-in
		this.afterThisFrame = new ArrayList<>();
		this.encoderIndex = 0;
		if (PASS_TEST_N > 0 && profiled(this.submitIndex) && this.submitIndex % 20 == 0) this.passTestPending = true;
		if (ENC_LOG) {
			this.encLabels = new String[512];
			this.encStats = new long[512][4];
			this.encGroupOpen = -1;
		}
		if (profiled(this.submitIndex) && !Native.profileBegin(this.enc, 512)) System.out.println("cadence-metal trace: no GPU timestamps on this device");
		if (this.passTestPending) {
			Native.passTest(this.enc, PASS_TEST_N, PASS_TEST_MODE);
			this.passTestPending = false;
		}
		int depth = record ? Math.max(MAX_IN_FLIGHT, RECORD_IN_FLIGHT) : maxFps ? Math.max(MAX_IN_FLIGHT, MAX_FPS_IN_FLIGHT) : MAX_IN_FLIGHT;
		while (this.inFlight.size() > depth) this.retire(this.inFlight.poll(), WaitStats.INFLIGHT);
		if (STATS) this.stats();
		if (WaitStats.ON) WaitStats.frame(this.submitIndex - 1);
	}

	/** Once a second: how much of each frame the render thread spent blocked on the GPU. Near zero means the CPU is the limit. */
	private void stats() {
		long now = System.nanoTime();
		this.statFrames++;
		if (this.statStart == 0) this.statStart = now;
		if (now - this.statStart < 1_000_000_000L) return;
		double frameMs = (now - this.statStart) / 1e6 / this.statFrames;
		System.out.printf("cadence-metal stats: %d fps, %.3f ms/frame, %.3f ms of it waiting on the GPU, %d presents%s%n", this.statFrames,
			frameMs, this.waitNanos / 1e6 / this.statFrames, this.statPresents, (PRESENT_QUEUE ? " (queued" + (PRESENT_ACQUIRE ? ", acquired on the present side" : "") + "; skipped so far " + Native.presentSkipped() + ", dropped " + Native.presentDropped() + ")" : "")
			+ (MetalSurface.PACE_ADAPT ? String.format(" (pace margin +%.2f ms learned)", Native.paceExtraMs()) : ""));
		if (MetalTerrain.OCC) {
			long[] t = this.terrain.lastCompletedCounts();
			System.out.printf("cadence-metal stats: terrain drew %d of %d quads (%.1f%%), %d before the split, %d chunks (%.1f quads each), %d frames drew terrain%n", t[0], t[1],
				100.0 * t[0] / Math.max(1, t[1]), t[2], t[3], (double) t[1] / Math.max(1, t[3]), this.terrain.terrainFrames - this.statTerrainFrames);
			this.statTerrainFrames = this.terrain.terrainFrames;
		}
		this.statStart = now;
		this.statFrames = 0;
		this.statPresents = 0;
		this.waitNanos = 0;
	}

	/** GPU timestamps cover the traced submit and the PROFILE_FRAMES - 1 after it; one frame alone is too noisy to compare shader variants. */
	/** -Dcadence.metal.profileSplit=true (with -Dcadence.metal.trace=N): profiled frames split the pass at every pipeline change. */
	static final boolean PROFILE_SPLIT = Boolean.getBoolean("cadence.metal.profileSplit");

	/** MetalRenderPass.setPipeline, profiled frames with PROFILE_SPLIT: a new render encoder (own timestamps) for the next draws. */
	/** Profiling (PROFILE_SPLIT): the open pass split here, with a label of its own (the own terrain's draws, which set no pipeline). */
	static void profileMark(String label) {
		if (!PROFILE_SPLIT) return;
		var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
		if (!(((com.mojang.renderpearl.frontend.FrontendCommandEncoder) device.createCommandEncoder()).backend() instanceof MetalEncoder e)) return;
		if (e.currentPass != null && e.profileSplit(label)) e.currentPass.restoreAfterSplit();
	}

	boolean profileSplit(String pipeline) {
		if (!PROFILE_SPLIT || !profiled(this.submitIndex) || this.encGroupOpen < 0) return false;
		Native.renderSplit(this.enc);
		int c = Native.profileCount(this.enc);
		if (c >= 4 && c / 4 - 1 < this.encLabels.length) {
			String pass = this.encGroupOpen < this.encLabels.length && this.encLabels[this.encGroupOpen] != null
				? this.encLabels[this.encGroupOpen].split(" / ")[0] : "?";
			this.encLabels[c / 4 - 1] = pass + " / " + pipeline;
			this.encGroupOpen = c / 4 - 1;
		}
		return true;
	}

	private static boolean profiled(long submit) {
		return ENC_LOG || TRACE_SUBMIT >= 0 && submit >= TRACE_SUBMIT && submit < TRACE_SUBMIT + PROFILE_FRAMES;
	}

	private void accumulateProfile(long cmd, long samples, int count) {
		// only submits shaped like the traced one (same encoder count) are averaged, so index e is the same pass in each
		if (this.profileShape < 0) this.profileShape = count;
		if (!ENC_LOG && count != this.profileShape) {
			this.profileSkipped++;
			if (samples != 0) Native.profileRead(this.ctx, samples, 0, 0);
			if (++this.profiledFrames < PROFILE_FRAMES) return;
			this.profiledFrames--;
			this.profileShape = -2; // finish below with what was kept
		}
		if (this.profileShape != -2) try (MemoryStack stack = MemoryStack.stackPush()) {
			long out = stack.nmalloc(8, Math.max(1, count) * 8);
			Native.profileRead(this.ctx, samples, count, out);
			this.profileSums[0] += Native.cmdGpuMicros(cmd);
			for (int e = 0; e < Math.min(count / 4, PROFILE_ENCODERS); e++) {
				double vs = MemoryUtil.memGetDouble(out + e * 32L), ve = MemoryUtil.memGetDouble(out + e * 32L + 8);
				double fs = MemoryUtil.memGetDouble(out + e * 32L + 16), fe = MemoryUtil.memGetDouble(out + e * 32L + 24);
				if (vs >= 0) this.profileSums[1 + 2 * e] += ve - vs;
				if (fs >= 0) this.profileSums[2 + 2 * e] += fe - fs;
				double[] at = {vs, ve, fs, fe};
				for (int k = 0; k < 4; k++) if (at[k] >= 0) { this.profileAt[4 * e + k] += at[k]; this.profileAtN[4 * e + k]++; }
			}
		}
		if (++this.profiledFrames < PROFILE_FRAMES) return;
		int kept = Math.max(1, PROFILE_FRAMES - this.profileSkipped);
		double[] avg = Arrays.stream(this.profileSums).map(v -> v / kept).toArray();
		System.out.printf("cadence-metal gpu: submits %d-%d took %.0f us on the GPU on average (%d of them shaped like the traced one)%n", TRACE_SUBMIT,
			TRACE_SUBMIT + PROFILE_FRAMES - 1, avg[0], kept);
		for (int e = 0; e < PROFILE_ENCODERS; e++) {
			if (avg[1 + 2 * e] + avg[2 + 2 * e] <= 0) continue;
			double[] at = new double[4];
			for (int k = 0; k < 4; k++) at[k] = this.profileAtN[4 * e + k] > 0 ? this.profileAt[4 * e + k] / this.profileAtN[4 * e + k] : -1;
			System.out.printf("cadence-metal gpu: #%d vertex %6.0f us, fragment %6.0f us; at vertex %.0f-%.0f, fragment %.0f-%.0f us  %s%n", e, avg[1 + 2 * e], avg[2 + 2 * e],
				at[0], at[1], at[2], at[3], this.traceLabels != null && e < this.traceLabels.length && this.traceLabels[e] != null ? this.traceLabels[e] : "");
		}
	}

	private void retire(Frame frame) {
		this.retire(frame, WaitStats.DRAIN);
	}

	private void retire(Frame frame, int site) {
		long waitStart = System.nanoTime();
		MetalEvents.Operation event = MetalEvents.begin("wait", frame.index, 0);
		try {
			Native.cmdWait(frame.cmd);
			if (WaitStats.ON) WaitStats.wait(site, System.nanoTime() - waitStart, this.submitIndex - frame.index);
			if (GPU_TIMES) GpuTimes.retire(frame.index, frame.cmd);
			GpuBusy.retired(frame.cmd);
			if (event != null) event.gpuMicros = Native.cmdGpuMicros(frame.cmd);
		} finally {
			MetalEvents.end(event);
		}
		this.waitNanos += System.nanoTime() - waitStart;
		frame.after.forEach(Runnable::run);
		Native.release(frame.cmd);
		this.completedIndex = frame.index;
	}

	void waitIdle() {
		while (!this.inFlight.isEmpty()) this.retire(this.inFlight.poll());
	}

	@Override
	public TransientMemory transientMemory() {
		return this.transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		if (FrameLog.COUNT) FrameLog.op(FrameLog.PASSES, 1);
		// Shaderpack runtime (-Dcadence.pack): the pass may draw into the pack's targets instead of its color attachments.
		MetalHooks.PassRedirector redirector = MetalHooks.redirector;
		MetalHooks.Redirect redirect = redirector == null ? null : redirector.redirect(descriptor);
		// A redirect that keeps the pass's attachments only hands its pipelines to the delegate (native shading's lite tier).
		PassDelegate keptDelegate = redirect != null && redirect.keepsAttachments() ? redirect.delegate() : null;
		if (keptDelegate != null) redirect = null;
		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = redirect != null ? List.of() : descriptor.colorAttachments();
		RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth = redirect != null && redirect.depth() != 0 ? null : descriptor.depthAttachment();
		// Anything sampled inside this pass must have its deferred clear in memory before the pass starts (with lazyClears: when it's bound)
		boolean lazy = LAZY_CLEARS && redirect == null && keptDelegate == null;
		for (MetalTexture t : List.copyOf(this.pendingClears)) {
			if (t.isClosed()) this.pendingClears.remove(t);
			else if (!lazy && !this.isAttachment(t, colors, depth)) this.flushClear(t);
		}
		if (FrameLog.ON && FrameLog.inTick()) {
			var c0 = descriptor.colorAttachments().isEmpty() ? null : descriptor.colorAttachments().get(0);
			MetalTexture.View v0 = c0 == null ? null : (MetalTexture.View) c0.textureView();
			String target = v0 == null ? "-" : v0.texture().getLabel() + " mip " + v0.baseMipLevel() + " " + v0.getWidth(0) + "x" + v0.getHeight(0);
			FrameLog.tickPass(descriptor.label().get() + " -> " + target + " area " + descriptor.renderArea(), v0 == null ? 0 : (long) v0.getWidth(0) * v0.getHeight(0));
		}
		int width = 0, height = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long colorHandles = stack.nmalloc(8, Math.max(1, colors.size()) * 8);
			long clears = stack.ncalloc(4, Math.max(1, colors.size()) * 5, 4);
			for (int i = 0; i < colors.size(); i++) {
				var attachment = colors.get(i);
				long handle = 0;
				if (attachment != null) {
					MetalTexture.View view = (MetalTexture.View) attachment.textureView();
					handle = view.handle;
					width = view.getWidth(0);
					height = view.getHeight(0);
					Vector4fc clear = attachment.clearValue().orElse(view.baseMipLevel() == 0 ? view.metalTexture().pendingColorClear : null);
					view.metalTexture().pendingColorClear = null;
					if (clear != null) putClear(clears + i * 20L, clear);
				}
				MemoryUtil.memPutAddress(colorHandles + i * 8L, handle);
			}
			if (redirect == null && keptDelegate == null && MetalPassHints.ENABLED) {
				// Cadence: the shader loader proved this pass overwrites every pixel of these attachments without reading them
				// (a pack's full-screen composite pass): don't load their old contents from memory (MetalPassHints; exact).
				int discard = MetalPassHints.consumeDiscardColors(descriptor);
				for (int i = 0; i < colors.size(); i++) if ((discard & (1 << i)) != 0) MemoryUtil.memPutFloat(clears + i * 20L, 2);
			}
			long depthHandle = 0;
			boolean clearDepth = false;
			double depthValue = 0;
			if (depth != null) {
				MetalTexture.View view = (MetalTexture.View) depth.textureView();
				MetalTexture texture = view.metalTexture();
				depthHandle = view.handle;
				if (colors.isEmpty()) {
					width = view.getWidth(0);
					height = view.getHeight(0);
				}
				OptionalDouble explicit = depth.clearValue();
				clearDepth = explicit.isPresent() || !Double.isNaN(texture.pendingDepthClear);
				depthValue = clearDepth ? explicit.orElse(texture.pendingDepthClear) : 0; // not NaN in a Load pass's descriptor (Metal API validation)
				texture.pendingDepthClear = Double.NaN;
			}
			this.forgetSettledClears();
			int colorCount = colors.size();
			if (redirect != null) {
				colorHandles = stack.nmalloc(8, Math.max(1, redirect.colors().length) * 8);
				clears = stack.ncalloc(4, Math.max(1, redirect.colors().length) * 5, 4);
				for (int i = 0; i < redirect.colors().length; i++) {
					MemoryUtil.memPutAddress(colorHandles + i * 8L, redirect.colors()[i]);
					float[] c = redirect.clears() == null ? null : redirect.clears()[i];
					if (c != null && c.length == 0) MemoryUtil.memPutFloat(clears + i * 20L, 2); // MetalBridge.DONT_CARE: not loaded
					else if (c != null) putClear(clears + i * 20L, new org.joml.Vector4f(c[0], c[1], c[2], c[3]));
				}
				colorCount = redirect.colors().length;
				width = redirect.width();
				height = redirect.height();
				if (redirect.depth() != 0) {
					depthHandle = redirect.depth();
					clearDepth = !Float.isNaN(redirect.depthClear());
					depthValue = clearDepth ? redirect.depthClear() : 0;
				}
			}
			if (dev.cadence.metal.cpu.Cpu.PASS_AB) { // opt-in A/B: lever on odd frames
				Native.cpuFlags(dev.cadence.metal.cpu.Cpu.nativeFlags((dev.cadence.metal.cpu.Ab.frame & 1) == 1));
				dev.cadence.metal.cpu.Cpu.PASS_TIMER.begin();
			}
			if (PROFILE_SPLIT) this.profileSplit("pass '" + descriptor.label().get() + "' begins" + (clearDepth ? " (depth clear)" : ""));
			int continued = Native.renderBegin(this.enc, colorCount, colorHandles, clears, depthHandle, clearDepth ? 1 : 0, (float) depthValue, width, height);
			// Render Max FPS: nothing reads a depth buffer after its pass (no world; each frame clears it again): not stored, a
			// third of the frame's memory traffic
			if (maxFps && depthHandle != 0) Native.discard(this.enc, depthHandle);
			if (dev.cadence.metal.cpu.Cpu.PASS_AB) dev.cadence.metal.cpu.Cpu.PASS_TIMER.end();
			this.passLazy = lazy && colorCount <= this.passColors.length;
			if (this.passLazy) {
				for (int i = 0; i < colorCount; i++) this.passColors[i] = MemoryUtil.memGetAddress(colorHandles + i * 8L);
				this.passColorCount = colorCount;
				this.passDepth = depthHandle;
				this.passWidth = width;
				this.passHeight = height;
			}
			if (redirect != null) redirect.delegate().begin(this.enc, depthHandle != 0);
			if (keptDelegate != null) keptDelegate.begin(this.enc, depthHandle != 0);
			MetalHooks.Labeler labeler = MetalHooks.labeler;
			if (labeler != null && continued == 0) labeler.label(this.enc, "game " + descriptor.label().get());
			if ((ENC_LOG || profiled(this.submitIndex)) && continued == 0) {
				int c = Native.profileCount(this.enc);
				if (c >= 4 && c / 4 - 1 < this.encLabels.length) this.encLabels[c / 4 - 1] = descriptor.label().get();
				this.encGroupOpen = c >= 4 ? c / 4 - 1 : -1;
			}
			if (this.submitIndex == TRACE_SUBMIT) {
				StringBuilder targets = new StringBuilder();
				for (int i = 0; i < colors.size(); i++) {
					var c = colors.get(i);
					targets.append(c == null ? "-" : c.textureView().texture().getLabel() + (MemoryUtil.memGetFloat(clears + i * 20L) != 0 ? "(clear)" : "")).append(' ');
				}
				if (depth != null) targets.append("depth=").append(depth.textureView().texture().getLabel()).append(clearDepth ? "(clear)" : "");
				String how = switch (continued) { case 1 -> "pass (merged)"; case 2 -> "pass (merged, depth cleared in place)"; default -> "pass #" + this.encoderIndex; };
				this.trace(how, "'" + descriptor.label().get() + "'", targets, width + "x" + height);
			}
			if (continued == 0) this.encoderIndex++;
		}
		var area = descriptor.renderArea();
		if (redirect != null && redirect.depth() != 0) {
			this.currentPass = new MetalRenderPass(this, true, 0, 0, redirect.width(), redirect.height(), redirect.delegate());
			return this.currentPass;
		}
		this.currentPass = new MetalRenderPass(this, depth != null, area.x(), area.y(), area.width(), area.height(),
			redirect != null ? redirect.delegate() : keptDelegate);
		if (redirect == null && !colors.isEmpty() && colors.get(0) != null && colors.get(0).textureView().texture() instanceof MetalTexture t && t.floatFrame)
			this.currentPass.floatColor = true;
		return this.currentPass;
	}

	private boolean isAttachment(MetalTexture t, List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors,
		RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth) {
		for (var c : colors) {
			if (c != null && c.textureView().texture() == t && c.textureView().baseMipLevel() == 0) return true;
		}
		return depth != null && depth.textureView().texture() == t;
	}

	private void forgetSettledClears() {
		this.pendingClears.removeIf(t -> !t.hasPendingClear());
	}

	private static void putClear(long at, Vector4fc c) {
		MemoryUtil.memPutFloat(at, 1);
		MemoryUtil.memPutFloat(at + 4, c.x());
		MemoryUtil.memPutFloat(at + 8, c.y());
		MemoryUtil.memPutFloat(at + 12, c.z());
		MemoryUtil.memPutFloat(at + 16, c.w());
	}

	@Override
	public void submitRenderPass() {
		if (this.currentPass == null) throw new IllegalStateException("No render pass to submit");
		this.currentPass.end();
		if (ENC_LOG && this.encGroupOpen >= 0 && this.encGroupOpen < 512) this.encStats[this.encGroupOpen][0] += this.currentPass.draws;
		this.trace("  end, draws:", this.currentPass.draws, "triangles:", this.currentPass.indices / 3);
		this.currentPass = null;
	}

	boolean inRenderPass() {
		return this.currentPass != null;
	}

	/** Cadence: one pair of uniform arrays kept between passes (MetalRenderPass.pending / bound), cleared. */
	private Object @Nullable [] spareA, spareB;

	Object[] uniformArray() {
		Object[] a = this.spareA;
		if (a != null) {
			this.spareA = null;
			return a;
		}
		a = this.spareB;
		if (a != null) {
			this.spareB = null;
			return a;
		}
		return new Object[MetalRenderPass.MAX_UNIFORMS];
	}

	void returnUniformArrays(Object[] a, Object[] b) {
		this.spareA = a;
		this.spareB = b;
	}

	/** Far terrain (dev.cadence.metal.lod): the open pass's pipeline state again after its own draw; false when no pass is open. */
	boolean reapplyPipeline() {
		if (this.currentPass == null) return false;
		this.currentPass.reapplyPipeline();
		return true;
	}

	/** The open pass was split by someone outside the backend (MetalBridge.restorePass); false when no pass is open. */
	boolean restorePass() {
		if (this.currentPass == null) return false;
		this.currentPass.restoreAfterSplit();
		return true;
	}

	/** Every deferred clear written now (before work that binds textures by handle: compute). */
	void flushPendingClears() {
		for (MetalTexture t : List.copyOf(this.pendingClears)) {
			if (t.isClosed()) this.pendingClears.remove(t);
			else this.flushClear(t);
		}
	}

	/** Forgets a pending clear of t: something is about to overwrite all of it (the shaderpack's final pass). */
	void dropPendingClear(MetalTexture t) {
		t.pendingColorClear = null;
		t.pendingDepthClear = Double.NaN;
		this.pendingClears.remove(t);
	}

	/**
	 * lazyClears: t (pending a clear) is about to be sampled inside the open pass: split the pass around writing the clear, then reopen
	 * the pass's attachments with load and restore its state. Without an open pass (or a pass opened the old way) it's flushClear.
	 */
	private static final int TRACE_SPLITS = Integer.getInteger("cadence.metal.traceSplits", 0);

	void flushClearInPass(MetalTexture t) {
		if (!t.hasPendingClear()) return;
		if (this.currentPass == null || !this.passLazy) {
			this.flushClear(t);
			return;
		}
		this.flushClear(t);  // (ends the open render encoder: other attachments)
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long colors = stack.nmalloc(8, Math.max(1, this.passColorCount) * 8);
			long clears = stack.ncalloc(4, Math.max(1, this.passColorCount) * 5, 4);  // all zero: load
			for (int i = 0; i < this.passColorCount; i++) MemoryUtil.memPutAddress(colors + i * 8L, this.passColors[i]);
			Native.renderBegin(this.enc, this.passColorCount, colors, clears, this.passDepth, 0, 0, this.passWidth, this.passHeight);
		}
		this.currentPass.restoreAfterSplit();
		this.lazySplits++;
		// -Dcadence.metal.traceSplits=N: where the first N splits come from (diagnostics)
		if (this.lazySplits <= TRACE_SPLITS) new Throwable("cadence-metal: lazy clear split " + this.lazySplits + " (" + t.getLabel() + ")").printStackTrace(System.out);
		long now = System.nanoTime();
		if (FrameHints.LOG_STATS && now - this.lazySplitLog > 10_000_000_000L) {
			System.out.println("cadence-metal: lazy clears split a pass " + this.lazySplits + " times so far (" + t.getLabel() + ")");
			this.lazySplitLog = now;
		}
	}

	/** Writes a deferred clear to memory with an empty pass: needed when the texture is read before it is drawn to. */
	void flushClear(MetalTexture t) {
		if (!t.hasPendingClear()) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			boolean depthFormat = t.getFormat().hasDepthAspect();
			long colors = stack.nmalloc(8, 8);
			long clears = stack.ncalloc(4, 5, 4);
			MemoryUtil.memPutAddress(colors, depthFormat ? 0 : t.handle);
			if (t.pendingColorClear != null) putClear(clears, t.pendingColorClear);
			boolean clearDepth = !Double.isNaN(t.pendingDepthClear);
			int continued = Native.renderBegin(this.enc, depthFormat ? 0 : 1, colors, clears, depthFormat ? t.handle : 0, clearDepth ? 1 : 0,
				clearDepth ? (float) t.pendingDepthClear : 0, t.getWidth(0), t.getHeight(0));
			this.trace(continued == 0 ? "flushClear #" + this.encoderIndex : "flushClear (in place)", t);
			if (continued == 0) this.encoderIndex++;
		}
		t.pendingColorClear = null;
		t.pendingDepthClear = Double.NaN;
		this.pendingClears.remove(t);
	}

	void flushClear(GpuTexture t) {
		this.flushClear((MetalTexture) t);
	}

	private void deferClear(GpuTexture texture, @Nullable Vector4fc color, double depth) {
		MetalTexture t = (MetalTexture) texture;
		if (t.getMipLevels() != 1) {
			// Mip chains (shader-pack buffers with mipmaps): level 0 only, as the game's OpenGL backend (and Iris) clear a
			// texture through a framebuffer bound at level 0; the other levels keep their contents until mipmaps are generated.
			// Clearing all 11 levels was 22 extra passes a frame with BSL. -Dcadence.metal.clearAllMips=true: every level.
			int levels = CLEAR_ALL_MIPS ? t.getMipLevels() : 1;
			for (int mip = 0; mip < levels; mip++) this.clearRegion(t, color, null, depth, 0, 0, t.getWidth(mip), t.getHeight(mip), mip);
			return;
		}
		if (color != null) t.pendingColorClear = color;
		if (!Double.isNaN(depth)) t.pendingDepthClear = depth;
		this.pendingClears.add(t);
		Native.discard(this.enc, t.handle); // old contents are dead: if the open encoder renders to t, skip writing it back
		this.trace("deferClear", t);
	}

	@Override
	public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
		this.deferClear(colorTexture, clearColor, Double.NaN);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
		this.deferClear(colorTexture, clearColor, Double.NaN);
		this.deferClear(depthTexture, null, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth,
		int x, int y, int width, int height, int mip) {
		if (mip == 0 && x == 0 && y == 0 && width == colorTexture.getWidth(0) && height == colorTexture.getHeight(0)) {
			this.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth);
		} else {
			this.clearRegion((MetalTexture) colorTexture, clearColor, (MetalTexture) depthTexture, clearDepth, x, y, width, height, mip);
		}
	}

	@Override
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		this.deferClear(depthTexture, null, clearDepth);
	}

	private void clearRegion(@Nullable MetalTexture color, @Nullable Vector4fc clearColor, @Nullable MetalTexture depth, double clearDepth,
		int x, int y, int width, int height, int mip) {
		this.trace("clearRect", color, depth, x, y, width, height, mip);
		if (color != null) this.flushClear(color);
		if (depth != null) this.flushClear(depth);
		Vector4fc c = clearColor != null ? clearColor : new org.joml.Vector4f();
		Native.clearRect(this.enc, color != null ? color.handle : 0, depth != null ? depth.handle : 0, c.x(), c.y(), c.z(), c.w(),
			Double.isNaN(clearDepth) ? -1 : (float) clearDepth, x, y, width, height, mip);
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		if (FrameLog.COUNT) {
			FrameLog.op(FrameLog.BUF_WRITES, 1);
			FrameLog.op(FrameLog.BUF_KB, data.remaining() >> 10);
		}
		MetalEvents.Operation event = MetalEvents.begin("upload", this.submitIndex, data.remaining());
		try {
			MetalBuffer dst = (MetalBuffer) destination.buffer();
			if (event != null) event.arena = dst.arena;
			// A blit would end the open render encoder, and wait for every command before it that reads dst. A buffer the GPU isn't
			// using can just be written, it is the same memory; one it is using can move to fresh memory if the write replaces all of it.
			boolean whole = destination.offset() == 0 && data.remaining() == dst.size();
			if (data.remaining() <= CPU_COPY_MAX && (this.idle(dst) || whole && dst.rename())) {
				this.trace("writeToBuffer (cpu)", data.remaining());
				if (UPLOAD_STATS) uploadStat(true, data.remaining(), dst.arena);
				MemoryUtil.memCopy(MemoryUtil.memAddress(data), dst.address + destination.offset(), data.remaining());
				if (dst.arena) this.terrain.invalidate(dst, destination.offset(), data.remaining());
				return;
			}
			if (UPLOAD_STATS) uploadStat(false, data.remaining(), dst.arena);
			this.trace("writeToBuffer", data.remaining());
			GpuBufferSlice staging = this.transientMemory.uploadGpu(data, 1L, GpuBuffer.USAGE_COPY_SRC);
			this.blitBuffer(this.use(staging.buffer()), staging.offset(), this.use(dst), destination.offset(), data.remaining());
		} finally {
			MetalEvents.end(event);
		}
	}

	/** In a frame that splits, Sodium geometry copied before its culling waits for the split (see MetalTerrain.hold); the rest goes in order. */
	private void blitBuffer(MetalBuffer src, long srcOffset, MetalBuffer dst, long dstOffset, long size) {
		if (dst.arena && this.terrain.split && !this.terrain.culledThisFrame()) {
			this.terrain.hold(src, srcOffset, dst, dstOffset, size);
		} else {
			if (src.arena || dst.arena) this.terrain.releaseHeld(false); // earlier arena copies land first
			Native.blitBuffer(this.enc, src.handle, srcOffset, dst.handle, dstOffset, size);
		}
		if (dst.arena) this.terrain.invalidate(dst, dstOffset, size);
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		if (FrameLog.COUNT) {
			FrameLog.op(FrameLog.BUF_COPIES, 1);
			FrameLog.op(FrameLog.COPY_KB, source.length() >> 10);
		}
		MetalEvents.Operation event = MetalEvents.begin("copy", this.submitIndex, source.length());
		try {
			MetalBuffer src = (MetalBuffer) source.buffer(), dst = (MetalBuffer) target.buffer();
			if (event != null) event.arena = src.arena || dst.arena;
			if (src.arena || dst.arena) MetalEvents.arenaTransfer("copy", source.length(), src.size(), dst.size());
			if (source.length() <= CPU_COPY_MAX && src != dst && this.idle(src) && this.idle(dst)) {
				this.trace("copyToBuffer (cpu)", source.length());
				MemoryUtil.memCopy(src.address + source.offset(), dst.address + target.offset(), source.length());
				if (dst.arena) this.terrain.invalidate(dst, target.offset(), source.length());
				return;
			}
			this.trace("copyToBuffer", source.length());
			this.blitBuffer(this.use(src), source.offset(), this.use(dst), target.offset(), source.length());
		} finally {
			MetalEvents.end(event);
		}
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mip, int layer, int x, int y, int width, int height) {
		if (FrameLog.COUNT) {
			FrameLog.op(FrameLog.TEX_WRITES, 1);
			FrameLog.op(FrameLog.TEX_PX, (long) width * height);
		}
		this.trace("writeToTexture", destination, width + "x" + height);
		this.flushClear(destination);
		GpuBufferSlice staging = this.transientMemory.uploadGpu(source, 16L, GpuBuffer.USAGE_COPY_SRC);
		int bytesPerRow = width * destination.getFormat().blockSize();
		Native.blitBufferToTexture(this.enc, this.use(staging.buffer()).handle, staging.offset(), bytesPerRow, bytesPerRow * height,
			((MetalTexture) destination).handle, layer, mip, x, y, width, height);
	}

	/** Cadence (MetalVolume): a 3D texture's whole level 0, depth slices one after another in {@code source}. */
	void writeToVolume(MetalTexture destination, ByteBuffer source, int width, int height, int depth) {
		this.trace("writeToVolume", destination, width + "x" + height + "x" + depth);
		GpuBufferSlice staging = this.transientMemory.uploadGpu(source, 16L, GpuBuffer.USAGE_COPY_SRC);
		int bytesPerRow = width * destination.getFormat().blockSize();
		Native.blitBufferToVolume(this.enc, this.use(staging.buffer()).handle, staging.offset(), bytesPerRow, bytesPerRow * height,
			destination.handle, width, height, depth);
	}

	@Override
	public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination,
		int destX, int destY, int copyWidth, int copyHeight, int mip, int layer) {
		this.trace("copyBufferToTexture", destination, copyWidth + "x" + copyHeight);
		this.flushClear(destination);
		int texel = destination.getFormat().blockSize();
		long skip = (sourceX + (long) sourceY * sourceWidth) * texel;
		Native.blitBufferToTexture(this.enc, this.use(source.buffer()).handle, source.offset() + skip, sourceWidth * texel,
			sourceWidth * texel * sourceHeight, ((MetalTexture) destination).handle, layer, mip, destX, destY, copyWidth, copyHeight);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mip) {
		this.copyTextureToBuffer(source, destination, offset, callback, mip, 0, 0, source.getWidth(mip), source.getHeight(mip));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mip, int x, int y, int width, int height) {
		this.trace("copyTextureToBuffer", source, width + "x" + height);
		this.flushClear(source);
		Native.blitTextureToBuffer(this.enc, ((MetalTexture) source).handle, mip, x, y, width, height, this.use(destination).handle, offset,
			width * source.getFormat().blockSize());
		this.afterGpuFinishes(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mip, int destX, int destY, int sourceX, int sourceY, int width, int height) {
		if (FrameLog.COUNT) FrameLog.op(FrameLog.TEX_COPIES, 1);
		this.trace("copyTextureToTexture", source, destination, width + "x" + height);
		this.flushClear(source);
		this.flushClear(destination);
		Native.blitTextureToTexture(this.enc, ((MetalTexture) source).handle, ((MetalTexture) destination).handle, mip, destX, destY, sourceX, sourceY,
			width, height);
	}

	@Override
	public GpuFence createFence() {
		long current = this.submitIndex;
		// opt-in (-Dcadence.cpu.fence): a fence made right after submit() with nothing recorded since (vanilla's uniform
		// ring rotates its slot there) covers exactly the work already committed, so it names the last submit instead of the
		// next, still empty one, and the ring's slot is free a frame sooner.
		long index = dev.cadence.metal.cpu.Cpu.FENCE && dev.cadence.metal.cpu.Cpu.fenceActive && current > 0 && Native.encEmpty(this.enc) ? current - 1 : current;
		if (dev.cadence.metal.cpu.Cpu.FENCE_STATS) dev.cadence.metal.cpu.Cpu.fenceMade(index != current);
		return new GpuFence() {
			@Override
			public boolean awaitCompletion(long timeoutNs) {
				if (MetalEncoder.this.completedIndex >= index) return true;
				if (index == MetalEncoder.this.submitIndex) {
					if (timeoutNs == 0) return false;
					throw new IllegalStateException("Cannot wait on a fence for the current submit");
				}
				boolean cpuStats = dev.cadence.metal.cpu.Cpu.FENCE_STATS && timeoutNs != 0;
				long t0 = WaitStats.ON || cpuStats ? System.nanoTime() : 0;
				while (MetalEncoder.this.completedIndex < index) {
					Frame oldest = MetalEncoder.this.inFlight.peek();
					if (timeoutNs == 0 && !Native.cmdDone(oldest.cmd)) return false;
					MetalEncoder.this.retire(MetalEncoder.this.inFlight.poll(), WaitStats.FENCE);
				}
				if (WaitStats.ON) WaitStats.fenceCaller(System.nanoTime() - t0);
				if (cpuStats) dev.cadence.metal.cpu.Cpu.fenceWaited(System.nanoTime() - t0);
				return true;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}

	/** -Dcadence.metal.presentQueue=acquire: this frame's image goes to a staging slot; the present side acquires the drawable and presents it. */
	void presentAcquire(long layer, GpuTextureView view) {
		MetalEvents.Operation event = MetalEvents.begin("present", this.submitIndex, 0);
		try {
			this.trace("present", view);
			this.presentedThisSubmit = true;
			this.flushClear(view.texture());
			if (Native.presentQueuedAcquire(this.enc, layer, ((MetalTexture.View) view).handle, GPU_TIMES && this.submitIndex < Integer.MAX_VALUE ? this.submitIndex : -1) == 0) return;
			if (GPU_TIMES) GpuTimes.presentQueued(this.submitIndex);
			this.statPresents++;
		} finally {
			MetalEvents.end(event);
		}
	}

	/** Record Mode: its frame's own texture (a native MTLTexture) presented instead of the game's target, after its fence. */
	void presentHandle(long drawable, long texture, long fence) {
		this.presentedThisSubmit = true;
		if (GPU_TIMES) GpuTimes.present(this.submitIndex, drawable);
		if (fence != 0) Native.presentFenced(this.enc, drawable, texture, fence);
		else Native.present(this.enc, drawable, texture);
		this.statPresents++;
	}

	void presentTexture(long drawable, GpuTextureView view) {
		MetalEvents.Operation event = MetalEvents.begin("present", this.submitIndex, 0);
		try {
			this.trace("present", view);
			this.presentedThisSubmit = true;
			this.flushClear(view.texture());
			if (PRESENT_QUEUE) {
				// the frame's command buffer only fills a staging slot; the present goes out on a second queue after the commit
				if (Native.presentQueued(this.enc, drawable, ((MetalTexture.View) view).handle) == 0) return; // slot still being read: skip
				if (GPU_TIMES) GpuTimes.present(this.submitIndex, drawable);
			} else {
				if (GPU_TIMES) GpuTimes.present(this.submitIndex, drawable);
				Native.present(this.enc, drawable, ((MetalTexture.View) view).handle);
			}
			this.statPresents++;
		} finally {
			MetalEvents.end(event);
		}
	}
}
