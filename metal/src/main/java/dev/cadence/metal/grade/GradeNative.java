package dev.cadence.metal.grade;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.cadence.metal.backend.MetalBridge;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;

/** Downcalls into mcgrade.m. */
final class GradeNative {
	private static final MethodHandle FRAME = Linker.nativeLinker().downcallHandle(
		MetalBridge.library().find("mcgr_frame").orElseThrow(() -> new IllegalStateException("missing native symbol mcgr_frame")),
		FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));

	private GradeNative() {
	}

	static int frame(long ctx, long enc, long color, long params) {
		try {
			return (int) FRAME.invokeExact(ctx, enc, color, params);
		} catch (Throwable t) {
			if (t instanceof RuntimeException r) throw r;
			if (t instanceof Error e) throw e;
			throw new RuntimeException(t);
		}
	}
}
