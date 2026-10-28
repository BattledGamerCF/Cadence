package dev.cadence.metal.framegen;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.cadence.metal.MetalBridge;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;

/** Downcalls into mcfg.m. Pointers cross as longs, as everywhere in the backend. */
final class FrameGenNative {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle NEW = fn("mcfg_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle SIZE = fn("mcfg_size", null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle SNAPSHOT = fn("mcfg_snapshot", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle IN_FRAME = fn("mcfg_present_in_frame", null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle ALONE = fn("mcfg_present_alone", null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle GPU_US = fn("mcfg_gpu_us", JAVA_DOUBLE, JAVA_LONG);
	private static final MethodHandle NEURAL = fn("mcfg_neural", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle DEBUG = fn("mcfg_debug", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG);

	private FrameGenNative() {
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

	static void size(long fg, long ctx, int w, int h) {
		try { SIZE.invokeExact(fg, ctx, w, h); } catch (Throwable t) { throw rethrow(t); }
	}

	static int snapshot(long fg, long enc, long color, long depth, long params, int len) {
		try { return (int) SNAPSHOT.invokeExact(fg, enc, color, depth, params, len); } catch (Throwable t) { throw rethrow(t); }
	}

	static void presentInFrame(long fg, long enc, long drawable, long latest, long frame, int len) {
		try { IN_FRAME.invokeExact(fg, enc, drawable, latest, frame, len); } catch (Throwable t) { throw rethrow(t); }
	}

	static void presentAlone(long fg, long ctx, long drawable, long latest, long frame, int len) {
		try { ALONE.invokeExact(fg, ctx, drawable, latest, frame, len); } catch (Throwable t) { throw rethrow(t); }
	}

	static int debug(long fg, long ctx, int src, long frame, int len, long out) {
		try { return (int) DEBUG.invokeExact(fg, ctx, src, frame, len, out); } catch (Throwable t) { throw rethrow(t); }
	}

	static double gpuMicros(long fg) {
		try { return (double) GPU_US.invokeExact(fg); } catch (Throwable t) { throw rethrow(t); }
	}

	/** Neural wanted or not (mcfg_neural): 2 ready, 1 starting, 0 off, -1 can't run here. */
	static int neural(long fg, long ctx, boolean on) {
		try {
			return (int) NEURAL.invokeExact(fg, ctx, on ? 1 : 0);
		} catch (Throwable t) {
			if (t instanceof RuntimeException r) throw r;
			if (t instanceof Error e) throw e;
			throw new RuntimeException(t);
		}
	}
}
