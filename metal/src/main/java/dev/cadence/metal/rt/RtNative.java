package dev.cadence.metal.rt;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.cadence.metal.MetalBridge;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;

/** Downcalls into mcrt.m. Pointers cross as longs, as everywhere in the backend. */
final class RtNative {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle NEW = fn("mcrt_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle HW = fn("mcrt_hw", JAVA_INT, JAVA_LONG);
	private static final MethodHandle SECTION = fn("mcrt_section", null, JAVA_LONG, JAVA_INT, JAVA_LONG);
	private static final MethodHandle FRAME = fn("mcrt_frame", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG);
	private static final MethodHandle FREE = fn("mcrt_free", null, JAVA_LONG);
	private static final MethodHandle PATH = fn("mcrt_path", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG);
	private static final MethodHandle PATH_DONE = fn("mcrt_path_done", null, JAVA_LONG);
	private static final MethodHandle BUFFER = fn("mcrt_buffer", JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle CONTENTS = fn("mcrt_contents", JAVA_LONG, JAVA_LONG);
	private static final MethodHandle RELEASE = fn("mcrt_release", null, JAVA_LONG);
	private static final MethodHandle SUPPORT = fn("mcrt_support", JAVA_INT, JAVA_LONG);

	private RtNative() {
	}

	private static MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return LINKER.downcallHandle(symbol, d);
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new RuntimeException(t);
	}

	static long create(long ctx, long source, boolean hw, int w, int h, long err, int errCap) {
		try { return (long) NEW.invokeExact(ctx, source, hw ? 1 : 0, w, h, err, errCap); } catch (Throwable t) { throw rethrow(t); }
	}

	static boolean hw(long rt) {
		try { return (int) HW.invokeExact(rt) != 0; } catch (Throwable t) { throw rethrow(t); }
	}

	static void section(long rt, int slot, long mask) {
		try { SECTION.invokeExact(rt, slot, mask); } catch (Throwable t) { throw rethrow(t); }
	}

	static int frame(long rt, long enc, long color, long depth, long lightmap, long vox, long bricks, long sections, long palette, long frame, int len,
		int sox, int soz, int flags, long lights) {
		try {
			return (int) FRAME.invokeExact(rt, enc, color, depth, lightmap, vox, bricks, sections, palette, frame, len, sox, soz, flags, lights);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int path(long rt, long enc, long color, long depth, long lightmap, long vox, long bricks, long sections, long palette, long frame, int len, boolean reset,
		boolean denoise, long viewProj) {
		try {
			return (int) PATH.invokeExact(rt, enc, color, depth, lightmap, vox, bricks, sections, palette, frame, len, reset ? 1 : 0, denoise ? 1 : 0, viewProj);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void pathDone(long rt) {
		try { PATH_DONE.invokeExact(rt); } catch (Throwable t) { throw rethrow(t); }
	}

	static void free(long rt) {
		try { FREE.invokeExact(rt); } catch (Throwable t) { throw rethrow(t); }
	}

	static long buffer(long ctx, long bytes) {
		try { return (long) BUFFER.invokeExact(ctx, bytes); } catch (Throwable t) { throw rethrow(t); }
	}

	static long contents(long buffer) {
		try { return (long) CONTENTS.invokeExact(buffer); } catch (Throwable t) { throw rethrow(t); }
	}

	static void release(long obj) {
		try { RELEASE.invokeExact(obj); } catch (Throwable t) { throw rethrow(t); }
	}

	/** 2: hardware ray tracing (M3 and newer), 1: Metal's ray tracing in software, 0: none. */
	static int support(long ctx) {
		try { return (int) SUPPORT.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
	}
}
