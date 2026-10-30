package dev.cadence.metal.net;

import io.netty.handler.codec.DecoderException;
import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * A stage's native state, freed exactly once: when its handler leaves the pipeline, or when the handler is collected without
 * ever having been added (a connection that closed while the stage was being set up).
 */
final class NativeHandle implements Runnable {
	private static final Cleaner CLEANER = Cleaner.create();
	private final AtomicLong value;
	private final LongConsumer free;
	private Cleaner.Cleanable cleanable;

	private NativeHandle(long value, LongConsumer free) {
		this.value = new AtomicLong(value);
		this.free = free;
	}

	/** h (non-zero) owned by owner from now on. */
	static NativeHandle of(Object owner, long h, LongConsumer free) {
		NativeHandle handle = new NativeHandle(h, free);
		handle.cleanable = CLEANER.register(owner, handle);
		return handle;
	}

	/** The live handle; a stage used after its removal is an error, never a native call on freed memory. */
	long get() {
		long h = value.get();
		if (h == 0) throw new DecoderException("network stage used after it was removed");
		return h;
	}

	void free() {
		cleanable.clean();
	}

	@Override
	public void run() {
		long h = value.getAndSet(0);
		if (h != 0) free.accept(h);
	}
}
