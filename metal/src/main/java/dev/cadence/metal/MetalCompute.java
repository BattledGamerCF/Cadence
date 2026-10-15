package dev.cadence.metal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.lwjgl.util.spvc.Spvc.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcMslConstexprSampler;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

/**
 * Cadence: compute kernels and storage resources for shader packs (Iris compute programs, custom images, buffer objects),
 * on the game's Metal device, encoded into the frame being recorded. Native side: mcpack.m.
 *
 * <p>Kernel resource layout, as the shader loader writes it: set 0 binding 0 the uniform block (Metal buffer 0), set 0
 * bindings 1..n combined samplers (textures 0..n-1, each sampler state compiled in), set 1 the pack's storage resources
 * (MetalStorage's slots, as for render pipelines).
 */
public final class MetalCompute {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle LIBRARY_NEW = fn("mc_pc_library_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle PIPELINE_NEW = fn("mc_pc_pipeline_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle MAX_THREADS = fn("mc_pc_max_threads", JAVA_INT, JAVA_LONG);
	private static final MethodHandle TEXTURE_NEW = fn("mc_pc_texture_new", JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle BUFFER_NEW = fn("mc_pc_buffer_new", JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle BEGIN = fn("mc_pc_begin", JAVA_LONG, JAVA_LONG);
	private static final MethodHandle END = fn("mc_pc_end", null, JAVA_LONG);
	private static final MethodHandle PIPELINE = fn("mc_pc_pipeline", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle TEXTURE = fn("mc_pc_texture", null, JAVA_LONG, JAVA_INT, JAVA_LONG);
	private static final MethodHandle BUFFER = fn("mc_pc_buffer", null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle BYTES = fn("mc_pc_bytes", null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle DISPATCH = fn("mc_pc_dispatch", null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle CLEAR_TEXTURE = fn("mc_pc_clear_texture", null, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle GENERATE_MIPS = fn("mc_pc_generate_mips", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle CLEAR_BUFFER = fn("mc_pc_clear_buffer", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_TEXTURE = fn("mc_pc_r_texture", null, JAVA_LONG, JAVA_INT, JAVA_LONG);
	private static final MethodHandle R_BUFFER = fn("mc_pc_r_buffer", null, JAVA_LONG, JAVA_INT, JAVA_LONG);

	private static MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		return LINKER.downcallHandle(Native.lookup().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name)), d);
	}

	private MetalCompute() {
	}

	/** The game's Metal encoder (its ctx and native Enc*), or null off the Metal backend. */
	private static MetalEncoder encoder() {
		return ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend() instanceof MetalEncoder e ? e : null;
	}

	public static boolean available() {
		return encoder() != null;
	}

	private static RuntimeException rethrow(Throwable t) {
		return t instanceof RuntimeException r ? r : new IllegalStateException(t);
	}

	/** A compiled kernel: its pipeline state and the most threads a threadgroup of it may have. */
	public record Kernel(long pipeline, int maxThreads) {
	}

	/** Compiles a kernel from Metal source (MSL 3.1, Safe math or Relaxed); throws with the compiler's message. */
	public static Kernel kernel(String msl, String entry, boolean relaxed) {
		MetalEncoder e = encoder();
		if (e == null) throw new IllegalStateException("not on the Metal backend");
		ByteBuffer source = MemoryUtil.memUTF8(msl);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, 4096, 1);
			long lib = (long) LIBRARY_NEW.invokeExact(e.ctx, MemoryUtil.memAddress(source), relaxed ? 1 : 0, err, 4096);
			if (lib == 0) throw new IllegalStateException("kernel " + entry + ": " + MemoryUtil.memUTF8(err));
			try {
				long pso = (long) PIPELINE_NEW.invokeExact(e.ctx, lib, MemoryUtil.memAddress(stack.UTF8(entry)), err, 4096);
				if (pso == 0) throw new IllegalStateException("kernel " + entry + ": " + MemoryUtil.memUTF8(err));
				return new Kernel(pso, (int) MAX_THREADS.invokeExact(pso));
			} finally {
				Native.release(lib);
			}
		} catch (Throwable t) {
			throw rethrow(t);
		} finally {
			MemoryUtil.memFree(source);
		}
	}

	/** A texture shaders and kernels may write: dims 2 or 3 (depth ignored for 2). */
	public static long texture(GpuFormat format, int dims, int width, int height, int depth, boolean renderTarget) {
		MetalEncoder e = encoder();
		try {
			long t = (long) TEXTURE_NEW.invokeExact(e.ctx, MetalConst.pixelFormat(format), dims, width, height, Math.max(1, depth), renderTarget ? 1 : 0);
			if (t == 0) throw new IllegalStateException("couldn't create a " + width + "x" + height + "x" + depth + " " + format + " texture");
			return t;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** A GPU buffer of zeros. */
	public static long buffer(long size) {
		try {
			return (long) BUFFER_NEW.invokeExact(encoder().ctx, size);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Releases a texture, buffer or kernel this class made, once the GPU is done with the frames that use it. */
	public static void release(long handle) {
		if (handle == 0) return;
		MetalEncoder e = encoder();
		if (e != null) e.releaseLater(handle);
		else Native.release(handle);
	}

	/** Every mip level of texture from its base level, in one blit command on the frame's encoder. */
	public static void generateMipmaps(long texture) {
		try {
			GENERATE_MIPS.invokeExact(encoder().enc, texture);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void clearTexture(long texture, int bytesPerPixel) {
		try {
			CLEAR_TEXTURE.invokeExact(encoder().enc, texture, bytesPerPixel);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void clearBuffer(long buffer) {
		try {
			CLEAR_BUFFER.invokeExact(encoder().enc, buffer);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderTexture(long enc, int slot, long texture) {
		try {
			R_TEXTURE.invokeExact(enc, slot, texture);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderBuffer(long enc, int slot, long buffer) {
		try {
			R_BUFFER.invokeExact(enc, slot, buffer);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** One compute encoder in the frame being recorded: set a kernel, its resources, dispatch; close ends it. */
	public static final class Pass implements AutoCloseable {
		private final long ce;
		private final MetalEncoder encoder;

		private Pass(MetalEncoder encoder, long ce) {
			this.encoder = encoder;
			this.ce = ce;
		}

		public void kernel(Kernel k) {
			try {
				PIPELINE.invokeExact(ce, k.pipeline());
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}

		public void texture(int slot, long texture) {
			try {
				TEXTURE.invokeExact(ce, slot, texture);
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}

		public void buffer(int slot, long buffer) {
			try {
				BUFFER.invokeExact(ce, slot, buffer, 0L);
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}

		/** Uniform data at buffer slot: inline when small (setBytes), else copied into a buffer the frame keeps. */
		public void bytes(int slot, ByteBuffer data) {
			int length = data.remaining();
			try {
				if (length <= 4096) {
					BYTES.invokeExact(ce, slot, MemoryUtil.memAddress(data), length);
				} else {
					long b = Native.bufferNew(encoder.ctx, length);
					MemoryUtil.memCopy(MemoryUtil.memAddress(data), Native.bufferContents(b), length);
					BUFFER.invokeExact(ce, slot, b, 0L);
					encoder.releaseLater(b);
				}
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}

		public void dispatch(int gx, int gy, int gz, int tx, int ty, int tz) {
			if (gx <= 0 || gy <= 0 || gz <= 0) return;
			try {
				DISPATCH.invokeExact(ce, gx, gy, gz, tx, ty, tz);
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}

		@Override
		public void close() {
			try {
				END.invokeExact(ce);
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}
	}

	/** Opens a compute encoder in the frame (whatever encoder is open ends first: call between render passes). */
	public static Pass begin() {
		MetalEncoder e = encoder();
		try {
			return new Pass(e, (long) BEGIN.invokeExact(e.enc));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** A sampler state compiled into a kernel: filter, wrap, mipmaps, depth comparison (less-or-equal). */
	public record SamplerState(boolean nearest, boolean repeat, boolean mipmaps, boolean compare) {
	}

	/**
	 * SPIR-V of a compute kernel to Metal source: {source, entry point}. samplers: the state of each set-0 combined sampler
	 * (binding i + 1); set1: the pack's storage resources (MetalStorage.Slot by binding).
	 */
	public static String[] translateKernel(ByteBuffer spirv, List<SamplerState> samplers, List<MetalStorage.Slot> set1) {
		return translate(spirv, Spv.SpvExecutionModelGLCompute, samplers, set1, true);
	}

	/** As translateKernel for one stage of a render pipeline's set-1 resources only (MetalPipeline). */
	static void bindSet1(MemoryStack stack, long compiler, int model, List<MetalStorage.Slot> set1) {
		for (MetalStorage.Slot s : set1) {
			SpvcMslResourceBinding b = SpvcMslResourceBinding.calloc(stack);
			spvc_msl_resource_binding_init(b);
			b.stage(model).desc_set(1).binding(s.binding()).msl_buffer(MetalStorage.BUFFER_BASE + s.binding())
				.msl_texture(MetalStorage.TEXTURE_BASE + s.binding()).msl_sampler(0);
			spvc_compiler_msl_add_resource_binding(compiler, b);
			if (s.sampled()) constexpr(stack, compiler, 1, s.binding(), new SamplerState(s.nearest(), false, false, false));
		}
	}

	private static void constexpr(MemoryStack stack, long compiler, int set, int binding, SamplerState state) {
		SpvcMslConstexprSampler d = SpvcMslConstexprSampler.calloc(stack);
		spvc_msl_constexpr_sampler_init(d);
		int filter = state.nearest() ? SPVC_MSL_SAMPLER_FILTER_NEAREST : SPVC_MSL_SAMPLER_FILTER_LINEAR;
		int address = state.repeat() ? SPVC_MSL_SAMPLER_ADDRESS_REPEAT : SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE;
		d.coord(SPVC_MSL_SAMPLER_COORD_NORMALIZED).min_filter(filter).mag_filter(filter)
			.mip_filter(state.mipmaps() ? SPVC_MSL_SAMPLER_MIP_FILTER_LINEAR : SPVC_MSL_SAMPLER_MIP_FILTER_NONE)
			.s_address(address).t_address(address).r_address(address)
			.compare_enable(state.compare()).compare_func(SPVC_MSL_SAMPLER_COMPARE_FUNC_LESS_EQUAL);
		spvc_compiler_msl_remap_constexpr_sampler_by_binding(compiler, set, binding, d);
	}

	/** samplers null: set 0 keeps SPIRV-Cross's own slot numbering (the offline check). */
	private static String[] translate(ByteBuffer spirv, int model, List<SamplerState> samplers, List<MetalStorage.Slot> set1, boolean kernel) {
		IntBuffer words = spirv.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(0, spvc_context_create(out), "context");
			long context = out.get(0);
			try {
				check(context, spvc_context_parse_spirv(context, words, words.remaining(), out), "parse");
				long ir = out.get(0);
				check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
				long compiler = out.get(0);
				check(context, spvc_compiler_create_compiler_options(compiler, out), "options");
				long options = out.get(0);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 30100);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
				// Cadence: uninitialized locals read as 0, as on desktop GL drivers (see MetalPipeline.translateNow)
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES, true);
				check(context, spvc_compiler_install_compiler_options(compiler, options), "install options");
				if (samplers != null) {
				SpvcMslResourceBinding ubo = SpvcMslResourceBinding.calloc(stack);
				spvc_msl_resource_binding_init(ubo);
				ubo.stage(model).desc_set(0).binding(0).msl_buffer(0).msl_texture(0).msl_sampler(0);
				spvc_compiler_msl_add_resource_binding(compiler, ubo);
				}
				for (int i = 0; samplers != null && i < samplers.size(); i++) {
					SpvcMslResourceBinding b = SpvcMslResourceBinding.calloc(stack);
					spvc_msl_resource_binding_init(b);
					b.stage(model).desc_set(0).binding(i + 1).msl_buffer(1 + i).msl_texture(i).msl_sampler(0);
					spvc_compiler_msl_add_resource_binding(compiler, b);
					constexpr(stack, compiler, 0, i + 1, samplers.get(i));
				}
				bindSet1(stack, compiler, model, set1);
				check(context, spvc_compiler_compile(compiler, out), "compile");
				return new String[] {MemoryUtil.memUTF8(out.get(0)), spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model)};
			} finally {
				spvc_context_destroy(context);
			}
		}
	}

	private static long headlessContext;

	/**
	 * Offline check (the pack census): one stage's SPIR-V (0 vertex, 1 fragment, 2 compute) through SPIRV-Cross and the
	 * Metal compiler on a context without a window. null when it compiles, else the first error.
	 */
	public static synchronized String check(ByteBuffer spirv, int stage) {
		try {
			if (headlessContext == 0) headlessContext = MetalBridge.createHeadlessContext();
			int model = stage == 0 ? Spv.SpvExecutionModelVertex : stage == 1 ? Spv.SpvExecutionModelFragment : Spv.SpvExecutionModelGLCompute;
			String msl = translate(spirv, model, null, List.of(), stage == 2)[0];
			ByteBuffer source = MemoryUtil.memUTF8(msl);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.ncalloc(1, 4096, 1);
				long lib = (long) LIBRARY_NEW.invokeExact(headlessContext, MemoryUtil.memAddress(source), 0, err, 4096);
				if (lib == 0) {
					String message = MemoryUtil.memUTF8(err);
					int at = message.indexOf("error:");
					return at < 0 ? message : message.substring(at).lines().findFirst().orElse(message);
				}
				Native.release(lib);
				return null;
			} finally {
				MemoryUtil.memFree(source);
			}
		} catch (Throwable t) {
			return String.valueOf(t.getMessage());
		}
	}

	private static void check(long context, int result, String step) {
		if (result != 0)
			throw new IllegalStateException("SPIRV-Cross " + step + " failed: " + (context != 0 ? spvc_context_get_last_error_string(context) : result));
	}
}
