package dev.cadence.metal.net;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.cadence.metal.backend.MetalBridge;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;

/** mcnet.c's functions (in libmcmetal). The per-packet calls are linked "critical": no thread-state change on Netty's threads. */
final class NetNative {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle INFLATER_NEW = fn("mc_net_inflater_new", false, JAVA_LONG);
	private static final MethodHandle INFLATE = fn("mc_net_inflate", true, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle INFLATER_FREE = fn("mc_net_inflater_free", false, null, JAVA_LONG);
	private static final MethodHandle CFB8_NEW = fn("mc_net_cfb8_new", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle CFB8_DECRYPT = fn("mc_net_cfb8_decrypt", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle CFB8_FREE = fn("mc_net_cfb8_free", false, null, JAVA_LONG);
	/**
	 * The same two calls as ordinary downcalls, for big packets: a critical call keeps its thread in Java's state throughout, so
	 * a GC safepoint waits for it; ~20 us for a usual packet, but 15-30 ms for a multi-MB one (BH10-08). Addresses are native.
	 */
	private static final MethodHandle INFLATE_BIG = fn("mc_net_inflate", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle CFB8_DECRYPT_BIG = fn("mc_net_cfb8_decrypt", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final long BIG = 256 * 1024;

	private NetNative() {
	}

	private static MethodHandle fn(String name, boolean critical, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return critical ? LINKER.downcallHandle(symbol, d, Linker.Option.critical(false)) : LINKER.downcallHandle(symbol, d);
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new RuntimeException(t);
	}

	static long inflaterNew() { try { return (long) INFLATER_NEW.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static long inflate(long h, long src, long n, long dst, long cap) {
		try {
			return cap > BIG ? (long) INFLATE_BIG.invokeExact(h, src, n, dst, cap) : (long) INFLATE.invokeExact(h, src, n, dst, cap);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}
	static void inflaterFree(long h) { try { INFLATER_FREE.invokeExact(h); } catch (Throwable t) { throw rethrow(t); } }
	static long cfb8New(long key) { try { return (long) CFB8_NEW.invokeExact(key); } catch (Throwable t) { throw rethrow(t); } }
	static void cfb8Decrypt(long h, long in, long out, long n) {
		try {
			if (n > BIG) CFB8_DECRYPT_BIG.invokeExact(h, in, out, n);
			else CFB8_DECRYPT.invokeExact(h, in, out, n);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}
	static void cfb8Free(long h) { try { CFB8_FREE.invokeExact(h); } catch (Throwable t) { throw rethrow(t); } }
}
