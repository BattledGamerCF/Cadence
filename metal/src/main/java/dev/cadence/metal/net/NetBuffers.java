package dev.cadence.metal.net;

import io.netty.buffer.ByteBuf;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/** A Netty buffer's readable bytes or writable space as a native address, for one handler's native calls. */
final class NetBuffers {
	/** Where a buffer without an address of its own (heap, composite) is copied to; grows, one per handler. */
	private ByteBuffer scratch;
	private long scratchAddress;

	/** The address of in's next n readable bytes (copied to scratch when in has no single native region). */
	long readable(ByteBuf in, int n) {
		if (in.hasMemoryAddress()) return in.memoryAddress() + in.readerIndex();
		if (in.isDirect() && in.nioBufferCount() == 1) return MemorySegment.ofBuffer(in.nioBuffer(in.readerIndex(), n)).address();
		if (scratch == null || scratch.capacity() < n) {
			scratch = ByteBuffer.allocateDirect(Math.max(n, 64 * 1024));
			scratchAddress = MemorySegment.ofBuffer(scratch).address();  // (taken at position 0: getBytes below moves it)
		}
		in.getBytes(in.readerIndex(), scratch.clear().limit(n));
		return scratchAddress;
	}

	/** The address of a direct buffer's writable space from its writer index (n bytes). */
	static long writable(ByteBuf out, int n) {
		if (out.hasMemoryAddress()) return out.memoryAddress() + out.writerIndex();
		return MemorySegment.ofBuffer(out.internalNioBuffer(out.writerIndex(), n)).address();
	}
}
