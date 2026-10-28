// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcMslConstexprSampler;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

import static org.lwjgl.util.spvc.Spvc.*;

/**
 * A pipeline is the frontend's SPIR-V run through SPIRV-Cross into Metal Shading Language, compiled by Metal.
 * Resource slots are fixed: uniform i lives in buffer/texture/sampler slot i, push constants in slot 26, vertex
 * buffers from slot 27. Vertex Y is flipped so render targets keep the same row order as OpenGL and Vulkan.
 */
public final class MetalPipeline implements BackendRenderPipeline {
	// -Dcadence.metal.gpuCount[=PATH] (measurement only, mcmetal.m): pipelines are named for the per-pipeline counts
	static final String GPU_COUNT_PATH = gpuCountPath();
	static final boolean GPU_COUNT = GPU_COUNT_PATH != null;

	private static String gpuCountPath() {
		String p = System.getProperty("cadence.metal.gpuCount");
		return p == null || p.equals("false") ? null : p.isEmpty() || p.equals("true") ? "gpucount.jsonl" : p;
	}

	/** Metal bakes the depth attachment format into the pipeline, so like Vulkan there's one for passes with depth and one without. */
	final long withDepth;
	final long withoutDepth;
	final long depthState;
	final List<BindGroupLayout.UniformDescription> uniforms;
	final boolean cull;
	final boolean wireframe;
	final float depthBiasConstant;
	final float depthBiasSlope;
	final int primitive;
	final String name;
	/** MTLCompareFunction of depthState, for MetalProbe. */
	final int depthCompare;
	/** Sodium solid/cutout terrain only, with MetalTerrain on: the same pipeline fetching its own vertices; 0 otherwise. */
	final long pulled;
	private final MetalEncoder encoder;
	private boolean closed;
	/** Cadence: Metal slot of each uniform for a shader pack's pipeline (packSlots); null: the uniform's index, as ours and vanilla's. */
	private final int @Nullable [] slots;

	/**
	 * The Metal argument-table slot of uniform {@code index} (buffer, or texture and sampler). Cadence: for a shader pack's
	 * sampler, the texture's slot in the low 16 bits and the sampler state's slot + 1 in the high ones (0: the same slot).
	 */
	int slot(int index) {
		return this.slots == null ? index : this.slots[index];
	}

	/** Cadence: whether uniform i lands in the same Metal slots here as in {@code other} (bindings kept across the switch). */
	boolean sameSlots(@Nullable MetalPipeline other) {
		return other != null && java.util.Arrays.equals(this.slots, other.slots);
	}

	/** Cadence: the high half of a sampler's slot when its sampler state is compiled into the shader (see packSlots). */
	static final int CONSTEXPR_SAMPLER = 0xFFFF;

	/**
	 * Cadence: the sampler state of each uniform packSlots made a constexpr sampler (null elsewhere), from the shader loader's
	 * statement (MetalPipelineSamplers) on this thread.
	 */
	static MetalSampler @Nullable [] constexprSamplers(CreateInfo info, int @Nullable [] slots) {
		if (slots == null) return null;
		java.util.Map<String, Object> groups = MetalPipelineSamplers.current();
		MetalSampler[] result = null;
		for (int i = 0; i < slots.length; i++) {
			if (slots[i] >>> 16 != CONSTEXPR_SAMPLER || groups == null) continue;
			if (result == null) result = new MetalSampler[slots.length];
			result[i] = (MetalSampler) groups.get(info.uniforms().get(i).name());
		}
		return result;
	}

	/** -Dcadence.cadence.packSlots=false: pack pipelines numbered like ours (uniform index = slot), for A/B runs. */
	private static final boolean PACK_SLOTS = !"false".equals(System.getProperty("cadence.cadence.packSlots"));

	/**
	 * Cadence: a shader pack's pipelines declare many more textures than vanilla's, and Metal has only 16 sampler slots per stage.
	 * Ours number every uniform by its index (buffers and textures share one count), which a pack with a few uniform buffers and
	 * ~15 textures would push past sampler 15. So pack pipelines get per-kind counters, like the shader loader's original renderer:
	 * uniform buffers from 0 (below PUSH_CONSTANTS_INDEX), combined image samplers from 0 (texture and sampler slot alike), texel
	 * buffers after the samplers. Null for every other pipeline (unchanged).
	 */
	static int @Nullable [] packSlots(CreateInfo info) {
		if (!PACK_SLOTS || !info.name().startsWith(PACK_NAMESPACE)) return null;
		var uniforms = info.uniforms();
		int samplers = 0;
		for (var u : uniforms) if (u.type() == com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER) samplers++;
		int[] slots = new int[uniforms.size()];
		int buffer = 0, sampler = 0, texture = 0, texel = samplers;
		// Cadence: more than 16 samplers: textures sampled alike share a sampler slot (MetalPipelineSamplers)
		java.util.Map<String, Object> groups = samplers > 16 ? MetalPipelineSamplers.current() : null;
		for (int i = 0; i < slots.length; i++) {
			switch (uniforms.get(i).type()) {
				case UNIFORM_BUFFER -> {
					if (buffer >= MetalConst.PUSH_CONSTANTS_INDEX) throw new IllegalArgumentException(info.name() + " exceeds " + MetalConst.PUSH_CONSTANTS_INDEX + " Metal uniform buffer slots");
					// Cadence: a pack's buffer objects start at MetalStorage.BUFFER_BASE
					if (buffer >= MetalStorage.BUFFER_BASE && MetalStorage.current() != null)
						throw new IllegalArgumentException(info.name() + " has more uniform buffers than fit below its buffer objects");
					slots[i] = buffer++;
				}
				case COMBINED_IMAGE_SAMPLER -> {
					if (texture >= 128) throw new IllegalArgumentException(info.name() + " exceeds 128 Metal texture slots");
					Object group = groups == null ? null : groups.get(uniforms.get(i).name());
					if (group instanceof MetalSampler) {
						// always this sampler state: compiled into the shader (a constexpr sampler), no sampler slot at all
						slots[i] = texture++ | CONSTEXPR_SAMPLER << 16;
						continue;
					}
					if (sampler >= 16) throw new IllegalArgumentException(info.name() + " exceeds 16 Metal sampler slots");
					slots[i] = texture++ | (sampler++ + 1) << 16;  // texture slot, sampler slot + 1 (see slot)
				}
				case TEXEL_BUFFER -> {
					if (texel >= 128) throw new IllegalArgumentException(info.name() + " exceeds 128 Metal texture slots");
					slots[i] = texel++;
				}
			}
		}
		return slots;
	}

	private MetalPipeline(MetalEncoder encoder, long withDepth, long withoutDepth, long pulled, long depthState, int depthCompare, CreateInfo info,
		int @Nullable [] slots) {
		this.encoder = encoder;
		this.slots = slots;
		this.withDepth = withDepth;
		this.withoutDepth = withoutDepth;
		this.pulled = pulled;
		this.depthState = depthState;
		this.depthCompare = depthCompare;
		this.name = info.name();
		this.info = info;
		this.uniforms = info.uniforms();
		this.cull = info.cull();
		this.wireframe = info.polygonMode() == PolygonMode.WIREFRAME;
		DepthStencilState depth = info.depthStencilState();
		this.depthBiasConstant = depth != null ? depth.depthBiasConstant() : 0;
		this.depthBiasSlope = depth != null ? depth.depthBiasScaleFactor() : 0;
		this.primitive = MetalConst.primitive(info.primitiveTopology());
	}

	/** Time spent creating pipelines so far, by step, for the bench's startup breakdown (read reflectively). */
	private static long compiled, translateNs, libraryNs, stateNs;

	public static synchronized Map<String, Object> compileStats() {
		return Map.of("count", compiled, "translateMs", translateNs / 1e6, "libraryMs", libraryNs / 1e6, "stateMs", stateNs / 1e6, "mslCacheHits", mslHits, "mslCacheMismatches", mslMismatches);
	}

	static @Nullable MetalPipeline compile(MetalEncoder encoder, CreateInfo info) {
		long ctx = encoder.ctx;
		long t0 = System.nanoTime(), t1 = t0, t2 = t0;
		CreateInfo.Shader vertex = shader(info, ShaderType.VERTEX), fragment = shader(info, ShaderType.FRAGMENT);
		long vlib = 0, flib = 0, plib = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			int[] slots = packSlots(info);
			MetalSampler[] constexpr = constexprSamplers(info, slots);
			java.util.List<MetalStorage.Slot> storage = info.name().startsWith(PACK_NAMESPACE) ? MetalStorage.current() : null;
			// Cadence (NativeShaders): a vanilla pipeline with a hand-written Metal program skips the translation
			NativeShaders.Source nat = slots == null && storage == null ? NativeShaders.source(info) : null;
			boolean isNative = nat != null && nat.ok();
			Translated v = isNative ? new Translated(nat.msl(), "native_vs") : translate(vertex.module(), info.uniforms().size(), slots, constexpr, storage);
			Translated f = isNative ? new Translated(nat.msl(), "native_fs") : translate(fragment.module(), info.uniforms().size(), slots, constexpr, storage);
			if (DUMP_DIR != null) dump(info.name(), v, f);
			if (OVERRIDE_DIR != null) {
				v = override(info.name() + ".vs.metal", v);
				f = override(info.name() + ".fs.metal", f);
			}
			if (UNCACHED) {
				v = new Translated("// " + UNCACHED_NONCE + "\n" + v.msl, v.entry);
				f = new Translated("// " + UNCACHED_NONCE + "\n" + f.msl, f.entry);
			}
			t1 = System.nanoTime();
			int[] modes = mathModes(info, fragment);
			if (storage != null) { // Cadence: images and buffer objects: MSL 3.1 (texture atomics)
				if (modes == null) modes = new int[] {MATH_SAFE | INVARIANT, MATH_SAFE | INVARIANT};
				modes = new int[] {modes[0] | MSL_31, modes[1] | MSL_31};
			}
			long withDepth = 0, withoutDepth = 0;
			DepthStencilState depth = info.depthStencilState();
			for (int attempt = 0; ; attempt++) {
				// (a native program is one source with both stages: compiled once, both functions from the one library)
				vlib = library(ctx, v.msl, modes == null ? -1 : modes[0], err);
				if (vlib != 0 && !isNative) flib = library(ctx, f.msl, modes == null ? -1 : modes[1], err);
				long fragmentLib = isNative ? vlib : flib;
				String failed = vlib == 0 || fragmentLib == 0 ? "didn't compile" : null;
				if (failed == null) {
					t2 = System.nanoTime();
					long vname = MemoryUtil.memAddress(stack.UTF8(v.entry)), fname = MemoryUtil.memAddress(stack.UTF8(f.entry));
					if (GPU_COUNT) Native.gpuCountLabel(info.name());  // -Dcadence.metal.gpuCount: names the per-pipeline counts
					withDepth = pipelineNewCounted(ctx, vlib, vname, fragmentLib, fname, describe(stack, info, true, true), err, 4096);
					if (withDepth != 0 && depth == null) {
						if (GPU_COUNT) Native.gpuCountLabel(info.name() + " (no depth)");
						withoutDepth = pipelineNewCounted(ctx, vlib, vname, fragmentLib, fname, describe(stack, info, false, true), err, 4096);
					}
					if (withDepth == 0 || depth == null && withoutDepth == 0) failed = "made no pipeline";
				}
				if (failed == null) break;
				if (!isNative || attempt > 0) {
					if (withDepth != 0) Native.release(withDepth);
					if (vlib == 0) throw new IllegalStateException(info.name() + " vertex: " + MemoryUtil.memUTF8(err) + "\n" + v.msl);
					if (fragmentLib == 0) throw new IllegalStateException(info.name() + " fragment: " + MemoryUtil.memUTF8(err) + "\n" + f.msl);
					throw new IllegalStateException(info.name() + ": " + MemoryUtil.memUTF8(err));
				}
				// a native program that doesn't compile, or whose pipeline Metal won't make: said once, translated as before
				System.out.println("[cadence] native shaders: " + info.name() + " " + failed + ", translated instead: " + MemoryUtil.memUTF8(err));
				NativeShaders.failed(nat.msl());
				if (withDepth != 0) Native.release(withDepth);
				withDepth = withoutDepth = 0;
				if (vlib != 0) Native.release(vlib);
				if (flib != 0) Native.release(flib);
				vlib = flib = 0;
				isNative = false;
				v = translate(vertex.module(), info.uniforms().size(), slots, constexpr, storage);
				f = translate(fragment.module(), info.uniforms().size(), slots, constexpr, storage);
				// (the same debugging hooks as the first attempt)
				if (DUMP_DIR != null) dump(info.name(), v, f);
				if (OVERRIDE_DIR != null) {
					v = override(info.name() + ".vs.metal", v);
					f = override(info.name() + ".fs.metal", f);
				}
				if (UNCACHED) {
					v = new Translated("// " + UNCACHED_NONCE + "\n" + v.msl, v.entry);
					f = new Translated("// " + UNCACHED_NONCE + "\n" + f.msl, f.entry);
				}
			}
			long pulled = 0;
			int compare = depth == null ? 7 : MetalConst.compare(depth.depthTest());
			long depthState = Native.depthStateNew(ctx, compare, depth != null && depth.writeDepth() ? 1 : 0);
			MetalPipeline pipeline = new MetalPipeline(encoder, withDepth, withoutDepth, pulled, depthState, compare, info, slots);
			pipeline.storage = storage;
			// (HDR's float variant is compiled from these later: the game's SPIR-V may be gone by then)
			pipeline.sources = new String[] {v.msl, v.entry, f.msl, f.entry};
			pipeline.modes = modes;
			pipeline.isNative = isNative;
			return pipeline;
		} finally {
			if (vlib != 0) Native.release(vlib);
			if (flib != 0) Native.release(flib);
			if (plib != 0) Native.release(plib);
			long t3 = System.nanoTime();
			synchronized (MetalPipeline.class) {
				compiled++;
				translateNs += t1 - t0;
				libraryNs += t2 - t1;
				stateNs += t3 - t2;
			}
		}
	}

	/**
	 * Cadence: compiles one stage's MSL. The source goes through native heap memory, not the thread's MemoryStack (64 KB by
	 * default): a shader pack's translated stage is easily larger and overflowed it ("Out of stack space" on world load with
	 * BSL). mode -1: mc_library_new (ours, unchanged); otherwise mc_library_new_mode's flags.
	 */
	private static long library(long ctx, String msl, int mode, long err) {
		java.nio.ByteBuffer source = MemoryUtil.memUTF8(msl);
		long t0 = System.nanoTime();
		try {
			long address = MemoryUtil.memAddress(source);
			return mode < 0 ? Native.libraryNew(ctx, address, err, 4096) : Native.libraryNewMode(ctx, address, mode, err, 4096);
		} finally {
			LIBRARY_NS.addAndGet(System.nanoTime() - t0);
			MemoryUtil.memFree(source);
		}
	}

	/** Time spent making shader libraries (from source) and pipeline states, summed over every thread, for the stall recorder. */
	static final java.util.concurrent.atomic.AtomicLong LIBRARY_NS = new java.util.concurrent.atomic.AtomicLong(), STATE_NS = new java.util.concurrent.atomic.AtomicLong();

	/** Cadence: the shader loader's pipelines (shaders/) are named in its namespace (RenderPipeline location = CreateInfo name). */
	private static final String PACK_NAMESPACE = "cadence_shaders:";
	/** -Dcadence.cadence.packMath=fast compiles pack pipelines like ours (Fast, no invariance), for A/B runs; default "policy". */
	private static final boolean PACK_MATH_POLICY = !"fast".equals(System.getProperty("cadence.cadence.packMath"));
	private static final int MATH_SAFE = 0, MATH_RELAXED = 1, INVARIANT = 4, MSL_31 = 8;

	/** Cadence: the pack's set-1 resources this pipeline's shaders use (MetalStorage), bound whenever it is set; null: none. */
	java.util.@Nullable List<MetalStorage.Slot> storage;

	/**
	 * Cadence: the arithmetic policy for a shader pack's pipeline, or null for ours and vanilla's (mc_library_new: Fast, which
	 * assumes no NaN or infinity; measured exact for them). A pack's shaders may rely on NaN/inf (sky, fog), so they get the
	 * policy of the loader's original renderer: Safe vertex stage; Relaxed fragment stage when the loader asked for it for
	 * exactly this pipeline (MetalPipelineMath) and its SPIR-V carries no precision requirement (SpirvMathPolicy), else Safe;
	 * both with preserveInvariance, which its paired depth and colour passes need.
	 */
	private static int @Nullable [] mathModes(CreateInfo info, CreateInfo.Shader fragment) {
		if (!PACK_MATH_POLICY || !info.name().startsWith(PACK_NAMESPACE)) return null;
		boolean requested = MetalPipelineMath.consumeRelaxedFragment(info.name());
		boolean relaxed = false;
		if (requested) {
			java.nio.ByteBuffer spv = fragment.module().spv().duplicate();
			byte[] bytes = new byte[spv.remaining()];
			spv.get(bytes);
			relaxed = SpirvMathPolicy.permitsRelaxedFragment(bytes);
			MetalPipelineMath.record(relaxed, relaxed ? MATH_RELAXED : MATH_SAFE);
		}
		return new int[] {MATH_SAFE | INVARIANT, (relaxed ? MATH_RELAXED : MATH_SAFE) | INVARIANT};
	}

	/** -Dcadence.metal.dumpMsl=DIR writes every pipeline's generated Metal source there, for reading what the GPU actually runs. */
	private static final @Nullable String DUMP_DIR = System.getProperty("cadence.metal.dumpMsl");
	/** -Dcadence.metal.overrideMsl=DIR uses DIR/NAME.{vs,fs}.metal instead of the generated source when present: shader experiments without a rebuild. */
	private static final @Nullable String OVERRIDE_DIR = System.getProperty("cadence.metal.overrideMsl");

	/**
	 * -Dcadence.metal.uncachedMsl=true gives every source a per-launch prefix so Metal's on-disk shader cache misses: what the
	 * first launch after install or a macOS update pays (about +0.7 s of boot on the M4 mini).
	 */
	private static final boolean UNCACHED = Boolean.getBoolean("cadence.metal.uncachedMsl");
	private static final long UNCACHED_NONCE = System.nanoTime();

	/** -Dcadence.metal.builtinMsl=false compiles Sodium's terrain from its own SPIR-V again, for A/B runs. */
	private static final boolean BUILTIN_MSL = Boolean.parseBoolean(System.getProperty("cadence.metal.builtinMsl", "true"));
	/**
	 * Pipelines that use the hand-written MSL in resources/cadence/metal/msl, with the ALPHA_CUTOUT define Sodium's
	 * ShaderChunkRenderer gives each. Only these exact names: Sodium's OIT variants are named differently and keep their SPIR-V.
	 */
	private static final Map<String, String> SODIUM_TERRAIN = Map.of(
		"sodium:pipeline/solid_terrain", "",
		"sodium:pipeline/cutout_terrain", "#define ALPHA_CUTOUT 0.5\n",
		"sodium:pipeline/translucent_terrain", "#define ALPHA_CUTOUT 0.01\n");

	private static String resource(String name) {
		try (InputStream in = MetalPipeline.class.getResourceAsStream("/cadence/metal/msl/" + name)) {
			return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String fileName(String pipelineName) {
		return pipelineName.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static void dump(String name, Translated v, Translated f) {
		try {
			Files.createDirectories(Path.of(DUMP_DIR));
			Files.writeString(Path.of(DUMP_DIR, fileName(name) + ".vs.metal"), v.msl);
			Files.writeString(Path.of(DUMP_DIR, fileName(name) + ".fs.metal"), f.msl);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Translated override(String name, Translated generated) {
		Path file = Path.of(OVERRIDE_DIR, fileName(name));
		if (!Files.exists(file)) return generated;
		try {
			System.out.println("cadence-metal: using " + file);
			return new Translated(Files.readString(file), generated.entry);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static CreateInfo.Shader shader(CreateInfo info, ShaderType type) {
		return info.shaders().stream().filter(s -> s.module().type() == type).findFirst()
			.orElseThrow(() -> new IllegalStateException(info.name() + " has no " + type + " shader"));
	}

	/** Packs the pipeline layout into the int array mc_pipeline_new reads (see mcmetal.m); no vertex input for shaders that pull their own. */
	private static long describe(MemoryStack stack, CreateInfo info, boolean hasDepth, boolean vertexInput) {
		IntBuffer d = stack.mallocInt(5 + info.vertexBuffers().size() * 3 + info.attribBindings().size() * 4 + info.colorTargetStates().size() * 9);
		List<CreateInfo.VertexBuffer> buffers = vertexInput ? info.vertexBuffers() : List.of();
		List<CreateInfo.AttribBinding> attributes = vertexInput ? info.attribBindings() : List.of();
		d.put(buffers.size());
		for (CreateInfo.VertexBuffer vb : buffers) d.put(MetalConst.VERTEX_BUFFER_BASE + vb.bufferSlot()).put(vb.stride()).put(vb.stepRate());
		d.put(attributes.size());
		for (CreateInfo.AttribBinding a : attributes) {
			d.put(a.location()).put(MetalConst.VERTEX_BUFFER_BASE + a.bufferSlot()).put(a.offset()).put(MetalConst.vertexFormat(a.format()));
		}
		d.put(info.colorTargetStates().size());
		for (@Nullable ColorTargetState c : info.colorTargetStates()) {
			if (c == null) {
				d.put(new int[9]);
				continue;
			}
			// MTLColorWriteMask is ABGR-ordered (red = 8), renderpearl's is RGBA-ordered (red = 1).
			int mask = (c.writeRed() ? 8 : 0) | (c.writeGreen() ? 4 : 0) | (c.writeBlue() ? 2 : 0) | (c.writeAlpha() ? 1 : 0);
			// (HDR: a variant for the game's float frame, forFloat)
			d.put(COMPILING_FLOAT.get() && c.format() == com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM ? 115 : MetalConst.pixelFormat(c.format())).put(mask);
			if (c.blendFunction().isPresent()) {
				var b = c.blendFunction().get();
				d.put(1).put(MetalConst.blendFactor(b.color().sourceFactor())).put(MetalConst.blendFactor(b.color().destFactor())).put(MetalConst.blendOp(b.color().op()))
					.put(MetalConst.blendFactor(b.alpha().sourceFactor())).put(MetalConst.blendFactor(b.alpha().destFactor())).put(MetalConst.blendOp(b.alpha().op()));
			} else {
				d.put(new int[] {0, 1, 0, 0, 1, 0, 0});
			}
		}
		d.put(hasDepth ? MetalConst.pixelFormat(com.mojang.renderpearl.api.GpuFormat.D32_FLOAT) : 0);
		d.put(MetalConst.topologyClass(info.primitiveTopology()));
		return MemoryUtil.memAddress(d.flip());
	}

	private record Translated(String msl, String entry) {
	}

	/**
	 * -Dcadence.startup.mslCache=true: SPIRV-Cross's output for a module is a pure function of its SPIR-V, stage and uniform count
	 * (and of this mod's and SPIRV-Cross's versions), so it is kept on disk under <game dir>/cadence-cache/msl/ and read back on the
	 * next launch instead of translated again (the translation is ~0.15-0.3 s of every startup on the main thread).
	 */
	private static final String MSL_MODE = System.getProperty("cadence.startup.mslCache", "false");
	/** true: use the cache; verify: use it and also translate every hit, counting any difference (mslCacheMismatches). */
	private static final boolean MSL_CACHE = MSL_MODE.equals("true") || MSL_MODE.equals("verify");
	static int mslMismatches;
	private static final java.nio.file.Path MSL_DIR = MSL_CACHE ? (System.getProperty("cadence.startup.mslCacheDir") != null
		? java.nio.file.Path.of(System.getProperty("cadence.startup.mslCacheDir"))
		: net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("cadence-cache").resolve("msl")) : null;
	/** What the translation depends on besides its inputs: this class's code (options, bindings) and the SPIRV-Cross build. */
	private static final String MSL_KEY_SALT = MSL_CACHE ? mslSalt() : null;

	static {
		// (at load, not at the first translation: with every pipeline found in the later caches no translation ever runs)
		if (MSL_CACHE) DiskCacheBudget.pruneOnce(MSL_DIR, Long.getLong("cadence.startup.mslCacheDisk", 256L << 20));
	}

	private static String mslSalt() {
		try (java.io.InputStream in = MetalPipeline.class.getResourceAsStream("MetalPipeline.class")) {
			byte[] code = in.readAllBytes();
			return "cadence-msl-1|" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(code))
				+ "|" + org.lwjgl.Version.getVersion();
		} catch (Exception e) {
			throw new IllegalStateException("mslCache salt", e);
		}
	}
	static int mslHits, mslMisses;

	private static Translated translate(SpvModule module, int uniformCount, int @Nullable [] slots, MetalSampler @Nullable [] constexpr,
		java.util.@Nullable List<MetalStorage.Slot> storage) {
		if (!MSL_CACHE) return translateNow(module, uniformCount, slots, constexpr, storage);
		String key;
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			md.update(MSL_KEY_SALT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			md.update((byte) module.type().ordinal());
			md.update(java.nio.ByteBuffer.allocate(4).putInt(uniformCount).array());
			if (slots != null) { // Cadence: a pack's slot layout is part of the translation's input
				java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(4 + slots.length * 4).putInt(-1);
				for (int x : slots) b.putInt(x);
				md.update(b.array());
			}
			if (storage != null) // Cadence: the pack's set-1 resources (kinds and filters, not handles)
				for (MetalStorage.Slot s : storage)
					md.update((s.binding() + (s.buffer() ? "b" : "t") + (s.sampled() ? "s" : "") + (s.nearest() ? "n" : "") + (s.repeat() ? "r" : "") + ";").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			if (constexpr != null) // Cadence: the sampler states compiled in (their settings, not their handles)
				for (MetalSampler c : constexpr) md.update(String.valueOf(c == null ? "-" : constexprKey(c)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			md.update(module.spv().duplicate());
			key = java.util.HexFormat.of().formatHex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			return translateNow(module, uniformCount, slots, constexpr, storage);
		}
		java.nio.file.Path file = MSL_DIR.resolve(key);
		try {
			String text = java.nio.file.Files.readString(file);
			int nl = text.indexOf('\n');
			if (nl > 0) {
				mslHits++;
				DiskCacheBudget.used(file);
				Translated hit = new Translated(text.substring(nl + 1), text.substring(0, nl));
				if (MSL_MODE.equals("verify") && !hit.equals(translateNow(module, uniformCount, slots, constexpr, storage))) mslMismatches++;
				return hit;
			}
		} catch (java.io.IOException missing) {
			// not cached yet
		}
		Translated t = translateNow(module, uniformCount, slots, constexpr, storage);
		mslMisses++;
		try {
			java.nio.file.Files.createDirectories(MSL_DIR);
			java.nio.file.Path tmp = java.nio.file.Files.createTempFile(MSL_DIR, key, ".tmp");
			java.nio.file.Files.writeString(tmp, t.entry + "\n" + t.msl);
			java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (java.io.IOException e) {
			// a cache that can't be written is just a slower launch
		}
		return t;
	}

	private static String constexprKey(MetalSampler c) {
		return c.getAddressModeU() + "," + c.getAddressModeV() + "," + c.getMinFilter() + "," + c.getMagFilter() + "," + c.getMaxAnisotropy()
			+ "," + c.getMaxLod() + "," + c.comparison() + ";";
	}

	/** Cadence: a sampler state as SPIR-V Cross's constexpr sampler, exactly as MetalSampler.make creates it. */
	private static void constexprSampler(MemoryStack stack, long compiler, int binding, MetalSampler c) {
		SpvcMslConstexprSampler d = SpvcMslConstexprSampler.calloc(stack);
		spvc_msl_constexpr_sampler_init(d);
		double lod = c.getMaxLod().orElse(1000.0);
		int address = c.getAddressModeU() == com.mojang.renderpearl.api.textures.AddressMode.REPEAT ? SPVC_MSL_SAMPLER_ADDRESS_REPEAT
			: SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE;
		int addressV = c.getAddressModeV() == com.mojang.renderpearl.api.textures.AddressMode.REPEAT ? SPVC_MSL_SAMPLER_ADDRESS_REPEAT
			: SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE;
		d.coord(SPVC_MSL_SAMPLER_COORD_NORMALIZED)
			.min_filter(MetalConst.filter(c.getMinFilter()))
			.mag_filter(MetalConst.filter(c.getMagFilter()))
			.mip_filter(lod > 0.25 ? SPVC_MSL_SAMPLER_MIP_FILTER_LINEAR : SPVC_MSL_SAMPLER_MIP_FILTER_NONE)
			.s_address(address).t_address(addressV).r_address(address)
			.lod_clamp_enable(true).lod_clamp_min(0).lod_clamp_max((float) Math.max(0.25, lod))
			.anisotropy_enable(c.getMaxAnisotropy() > 1).max_anisotropy(Math.max(1, c.getMaxAnisotropy()))
			.compare_enable(c.comparison()).compare_func(SPVC_MSL_SAMPLER_COMPARE_FUNC_LESS_EQUAL);
		spvc_compiler_msl_remap_constexpr_sampler_by_binding(compiler, 0, binding, d);
	}

	private static Translated translateNow(SpvModule module, int uniformCount, int @Nullable [] slots, MetalSampler @Nullable [] constexpr,
		java.util.@Nullable List<MetalStorage.Slot> storage) {
		boolean vertex = module.type() == ShaderType.VERTEX;
		int model = vertex ? Spv.SpvExecutionModelVertex : Spv.SpvExecutionModelFragment;
		IntBuffer spirv = module.spv().asIntBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(0, spvc_context_create(out), "context");
			long context = out.get(0);
			try {
				check(context, spvc_context_parse_spirv(context, spirv, spirv.remaining(), out), "parse");
				long ir = out.get(0);
				check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
				long compiler = out.get(0);
				check(context, spvc_compiler_create_compiler_options(compiler, out), "options");
				long options = out.get(0);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, storage != null ? 30100 : 30000);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
				if (vertex) spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
				// Cadence: a GLSL variable read before any write is 0 on the desktop drivers packs are written against; in MSL it
				// is whatever the register held (Pegasus's pre_reflection, unset off metals: red/blue/magenta halos at the waterline)
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES, true);
				check(context, spvc_compiler_install_compiler_options(compiler, options), "install options");
				for (int i = 0; i < uniformCount; i++) bind(stack, compiler, model, 0, i, slots == null ? i : slots[i]);
				if (constexpr != null)
					for (int i = 0; i < constexpr.length; i++) if (constexpr[i] != null) constexprSampler(stack, compiler, i, constexpr[i]);
				if (storage != null) MetalCompute.bindSet1(stack, compiler, model, storage);
				bind(stack, compiler, model, SPVC_MSL_PUSH_CONSTANT_DESC_SET, SPVC_MSL_PUSH_CONSTANT_BINDING, MetalConst.PUSH_CONSTANTS_INDEX);
				check(context, spvc_compiler_compile(compiler, out), "compile");
				String msl = MemoryUtil.memUTF8(out.get(0));
				return new Translated(msl, spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model));
			} finally {
				spvc_context_destroy(context);
			}
		}
	}

	/** slot: Cadence: a texture's slot in the low 16 bits and its sampler state's slot + 1 in the high ones (see slot). */
	private static void bind(MemoryStack stack, long compiler, int model, int set, int binding, int slot) {
		SpvcMslResourceBinding b = SpvcMslResourceBinding.calloc(stack);
		spvc_msl_resource_binding_init(b);
		int low = slot & 0xFFFF, high = slot >>> 16;
		b.stage(model).desc_set(set).binding(binding).msl_buffer(low).msl_texture(low)
			.msl_sampler(high == 0 ? low : high == CONSTEXPR_SAMPLER ? 0 : high - 1);
		spvc_compiler_msl_add_resource_binding(compiler, b);
	}

	private static void check(long context, int result, String step) {
		if (result != 0) {
			throw new IllegalStateException("SPIRV-Cross " + step + " failed: " + (context != 0 ? spvc_context_get_last_error_string(context) : result));
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	/** The CreateInfo it was made from, and its Metal sources (vertex msl, entry, fragment msl, entry), for HDR's float variant. */
	private final CreateInfo info;
	private String @Nullable [] sources;
	private int @Nullable [] modes;
	private boolean isNative;
	/** HDR (dev.cadence.metal.Hdr): the same pipeline for the float frame target, made at its first use there. */
	private @Nullable MetalPipeline floatVariant;
	private boolean noFloatVariant, variant;
	/** describe(): RGBA8 colour targets as RGBA16Float (render thread: pipelines compile on it). */
	// (per thread: worker threads describe other pipelines while the render thread compiles a float variant, BH7 suspicion 1)
	private static final ThreadLocal<Boolean> COMPILING_FLOAT = ThreadLocal.withInitial(() -> false);

	/**
	 * HDR: this pipeline for a pass into the float frame (the main target's colour is RGBA16Float there; a pipeline made for RGBA8
	 * draws garbage into it): the same Metal sources and states, the colour targets RGBA16Float. Itself when it has no RGBA8
	 * colour target, or when the variant can't be made.
	 */
	MetalPipeline forFloat() {
		if (this.floatVariant != null) return this.floatVariant;
		if (this.noFloatVariant) return this;
		boolean rgba8 = false;
		for (@Nullable ColorTargetState c : this.info.colorTargetStates()) rgba8 |= c != null && c.format() == com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM;
		this.noFloatVariant = true;
		if (!rgba8 || this.sources == null) return this;
		long ctx = this.encoder.ctx, vlib = 0, flib = 0, withDepth = 0, withoutDepth = 0;
		COMPILING_FLOAT.set(true);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			vlib = library(ctx, this.sources[0], this.modes == null ? -1 : this.modes[0], err);
			if (vlib != 0 && !this.isNative) flib = library(ctx, this.sources[2], this.modes == null ? -1 : this.modes[1], err);
			long fragmentLib = this.isNative ? vlib : flib;
			if (vlib != 0 && fragmentLib != 0) {
				long vname = MemoryUtil.memAddress(stack.UTF8(this.sources[1])), fname = MemoryUtil.memAddress(stack.UTF8(this.sources[3]));
				withDepth = pipelineNewCounted(ctx, vlib, vname, fragmentLib, fname, describe(stack, this.info, true, true), err, 4096);
				if (withDepth != 0 && this.withoutDepth != 0)
					withoutDepth = pipelineNewCounted(ctx, vlib, vname, fragmentLib, fname, describe(stack, this.info, false, true), err, 4096);
			}
			if (withDepth == 0 || this.withoutDepth != 0 && withoutDepth == 0) {
				if (withDepth != 0) Native.release(withDepth);
				System.out.println("[cadence] HDR: no float variant of " + this.name + ": " + MemoryUtil.memUTF8(err));
				return this;
			}
		} finally {
			COMPILING_FLOAT.set(false);
			if (vlib != 0) Native.release(vlib);
			if (flib != 0) Native.release(flib);
		}
		MetalPipeline v = new MetalPipeline(this.encoder, withDepth, withoutDepth, 0, this.depthState, this.depthCompare, this.info, this.slots);
		v.variant = true;  // (shares this one's depth state: closing it releases only its own pipelines)
		v.storage = this.storage;
		this.floatVariant = v;
		this.noFloatVariant = false;
		return v;
	}

	@Override
	public void close() {
		if (this.floatVariant != null) this.floatVariant.close();
		if (!this.closed) {
			this.closed = true;
			this.encoder.releaseLater(this.withDepth);
			if (this.withoutDepth != 0) this.encoder.releaseLater(this.withoutDepth);
			if (this.pulled != 0) this.encoder.releaseLater(this.pulled);
			if (!this.variant) this.encoder.releaseLater(this.depthState);
		}
	}

	/** Native.pipelineNew, counted per frame by the frame log (-Dcadence.own.int.frameLog). */
	private static long pipelineNewCounted(long ctx, long vlib, long vname, long flib, long fname, long desc, long err, int errLength) {
		if (FrameLog.COUNT) FrameLog.op(FrameLog.PIPELINES, 1);
		long t0 = System.nanoTime();
		try {
			return Native.pipelineNew(ctx, vlib, vname, flib, fname, desc, err, errLength);
		} finally {
			STATE_NS.addAndGet(System.nanoTime() - t0);
		}
	}
}
