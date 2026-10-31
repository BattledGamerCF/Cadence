package dev.cadence.metal.neural;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.cadence.metal.backend.MetalBridge;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;

/** Downcalls into mcneural.m. Pointers cross as longs, as everywhere in the backend. */
final class NeuralNative {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle ST_NEW = fn("mcst_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle ST_FRAME = fn("mcst_frame", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_FLOAT);
	private static final MethodHandle ST_STATS = fn("mcst_stats", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle ST_FREE = fn("mcst_free", null, JAVA_LONG);
	private static final MethodHandle SM_UPSCALE = fn("mcsm_upscale", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle SM_ANIM = fn("mcsm_anim", null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle SM_LEVELS = fn("mcsm_copy_levels", null, JAVA_LONG, JAVA_LONG, JAVA_LONG);

	private NeuralNative() {
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

	static long styleNew(long ctx, long model, long compiled, int margin, long err, int errCap) {
		try { return (long) ST_NEW.invokeExact(ctx, model, compiled, margin, err, errCap); } catch (Throwable t) { throw rethrow(t); }
	}

	static int styleFrame(long st, long enc, long color, float strength) {
		try { return (int) ST_FRAME.invokeExact(st, enc, color, strength); } catch (Throwable t) { throw rethrow(t); }
	}

	static void styleStats(long st, long out) {
		try { ST_STATS.invokeExact(st, out); } catch (Throwable t) { throw rethrow(t); }
	}

	static int smoothUpscale(long src, int w, int h, long out, long rects, int n, long err, int errCap) {
		try { return (int) SM_UPSCALE.invokeExact(src, w, h, out, rects, n, err, errCap); } catch (Throwable t) { throw rethrow(t); }
	}

	static void smoothAnim(long ctx, long enc, long src, long dst, long rects, int n, int maxW, int maxH) {
		try { SM_ANIM.invokeExact(ctx, enc, src, dst, rects, n, maxW, maxH); } catch (Throwable t) { throw rethrow(t); }
	}

	static void smoothCopyLevels(long enc, long src, long dst) {
		try { SM_LEVELS.invokeExact(enc, src, dst); } catch (Throwable t) { throw rethrow(t); }
	}

	static void styleFree(long st) {
		try { ST_FREE.invokeExact(st); } catch (Throwable t) { throw rethrow(t); }
	}
}
