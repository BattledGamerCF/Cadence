package dev.cadence.metal.record;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.cadence.metal.MetalBridge;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;

/** Downcalls into mcrecord.m and the backend's buffer helpers. Pointers cross as longs, as everywhere in the backend. */
final class RecordNative {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle NEW = fn("mcrm_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle SIZE = fn("mcrm_size", null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle FRAME = fn("mcrm_frame", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle MID = fn("mcrm_mid", JAVA_LONG, JAVA_LONG);
	private static final MethodHandle RELEASE = fn("mcrm_release", null, JAVA_LONG);
	private static final MethodHandle READ = fn("mcrm_read", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle GPU_US = fn("mcrm_gpu_us", java.lang.foreign.ValueLayout.JAVA_DOUBLE);
	private static final MethodHandle BUFFER_NEW = fn("mc_buffer_new", JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle BUFFER_CONTENTS = fn("mc_buffer_contents", JAVA_LONG, JAVA_LONG);
	private static final MethodHandle OBJ_RELEASE = fn("mc_release", null, JAVA_LONG);

	private RecordNative() {
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

	static long create(long ctx, long source, long err, int errCap) {
		try { return (long) NEW.invokeExact(ctx, source, err, errCap); } catch (Throwable t) { throw rethrow(t); }
	}

	static void size(long rec, long ctx, int w, int h) {
		try { SIZE.invokeExact(rec, ctx, w, h); } catch (Throwable t) { throw rethrow(t); }
	}

	static long frame(long rec, long enc, long frame, int frameLength, long grid, long palette, long entities, long hud, long occ,
		long light, int interpolate) {
		try {
			return (long) FRAME.invokeExact(rec, enc, frame, frameLength, grid, palette, entities, hud, occ, light, interpolate);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long mid(long rec) {
		try { return (long) MID.invokeExact(rec); } catch (Throwable t) { throw rethrow(t); }
	}

	static int read(long rec, long ctx, long dst) {
		try { return (int) READ.invokeExact(rec, ctx, dst); } catch (Throwable t) { throw rethrow(t); }
	}

	static double gpuMicros() {
		try { return (double) GPU_US.invokeExact(); } catch (Throwable t) { throw rethrow(t); }
	}

	static void release(long rec) {
		try { RELEASE.invokeExact(rec); } catch (Throwable t) { throw rethrow(t); }
	}

	static long buffer(long ctx, long size) {
		try { return (long) BUFFER_NEW.invokeExact(ctx, size); } catch (Throwable t) { throw rethrow(t); }
	}

	static long contents(long buffer) {
		try { return (long) BUFFER_CONTENTS.invokeExact(buffer); } catch (Throwable t) { throw rethrow(t); }
	}

	static void releaseObject(long obj) {
		try { OBJ_RELEASE.invokeExact(obj); } catch (Throwable t) { throw rethrow(t); }
	}
}
