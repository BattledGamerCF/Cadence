package dev.cadence.metal;

import java.util.List;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Cadence: a shader pack's resources outside the game's binding model (Iris custom images, buffer objects, 3D textures
 * and their samplers). Shaders declare them in descriptor set 1; the game's pipeline reflection never sees that set
 * (ShaderSpirvReflectionMixin), and the backend binds them itself at fixed Metal slots: binding k at texture slot
 * TEXTURE_BASE + k or buffer slot BUFFER_BASE + k, a sampler of one compiled into the shader (constexpr). The shader
 * loader states a pipeline's set-1 bindings around its compilation on the compiling thread (open), and the pipeline binds
 * them whenever it is set in a render pass, asking each resource for its current native handle.
 */
public final class MetalStorage {
	/** Texture slots 96..127 (Metal has 128 per stage); the game's samplers use the slots from 0. */
	public static final int TEXTURE_BASE = 96;
	/** Buffer slots 16..25: below the push constants (26) and vertex buffers (27..30); the game's uniform buffers use 0... */
	public static final int BUFFER_BASE = 16;
	public static final int MAX_BINDINGS = 10;

	/**
	 * Set-1 binding {@code binding}: a buffer (buffer object) or a texture (image or sampled texture); sampled: a combined
	 * sampler, with {@code nearest} its filter, clamped to edge unless {@code repeat} (a pack's tiling noise volume). handle: the
	 * native object now (it can change size).
	 */
	public record Slot(int binding, boolean buffer, boolean sampled, boolean nearest, boolean repeat, LongSupplier handle) {
		public Slot(int binding, boolean buffer, boolean sampled, boolean nearest, LongSupplier handle) {
			this(binding, buffer, sampled, nearest, false, handle);
		}
	}

	private static final ThreadLocal<List<Slot>> COMPILE = new ThreadLocal<>();
	private static final ThreadLocal<Boolean> WRITABLE = new ThreadLocal<>();

	/**
	 * Textures created on this thread until the handle closes get ShaderWrite usage: a pack's colour targets its programs
	 * store to as colorimgN (Iris). Only those: the usage can cost Apple GPUs their lossless compression.
	 */
	public static AutoCloseable writableTextures() {
		WRITABLE.set(Boolean.TRUE);
		return WRITABLE::remove;
	}

	static boolean creatingWritable() {
		return WRITABLE.get() != null;
	}

	private MetalStorage() {
	}

	/** States the set-1 bindings of the pipelines compiled on this thread until the handle is closed. */
	public static AutoCloseable open(List<Slot> slots) {
		List<Slot> previous = COMPILE.get();
		COMPILE.set(List.copyOf(slots));
		return () -> {
			if (previous == null) COMPILE.remove();
			else COMPILE.set(previous);
		};
	}

	static @Nullable List<Slot> current() {
		List<Slot> slots = COMPILE.get();
		return slots == null || slots.isEmpty() ? null : slots;
	}

	/** Binds a pipeline's set-1 resources in the open render encoder. */
	static void bind(long enc, List<Slot> slots) {
		for (Slot s : slots) {
			long h = s.handle().getAsLong();
			if (h == 0) continue;
			if (s.buffer()) MetalCompute.renderBuffer(enc, BUFFER_BASE + s.binding(), h);
			else MetalCompute.renderTexture(enc, TEXTURE_BASE + s.binding(), h);
		}
	}
}
