package dev.cadence.metal.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.VarInt;

/**
 * Minecraft's decompressing stage (CompressionDecoder) on the system's zlib (FastNet): the same frames, checks and errors;
 * the packet's declared size is the output buffer, as Minecraft's Inflater call has it.
 */
final class FastInflate extends CompressionDecoder {
	private final NetBuffers buffers = new NetBuffers();
	private long handle;
	private int threshold;
	private boolean validate;

	FastInflate(int threshold, boolean validate) {
		super(threshold, validate);
		this.threshold = threshold;
		this.validate = validate;
		handle = NetNative.inflaterNew();
		if (handle == 0) throw new IllegalStateException("no native inflater");
	}

	@Override
	public void setThreshold(int threshold, boolean validate) {
		super.setThreshold(threshold, validate);
		this.threshold = threshold;
		this.validate = validate;
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		int size = VarInt.read(in);
		if (size == 0) {
			out.add(in.readBytes(in.readableBytes()));
			return;
		}
		if (validate) {
			if (size < threshold) throw new DecoderException("Badly compressed packet - size of " + size + " is below server threshold of " + threshold);
			if (size > MAXIMUM_UNCOMPRESSED_LENGTH)
				throw new DecoderException("Badly compressed packet - size of " + size + " is larger than protocol maximum of " + MAXIMUM_UNCOMPRESSED_LENGTH);
		}
		int n = in.readableBytes();
		ByteBuf inflated = ctx.alloc().directBuffer(size);
		try {
			long made = NetNative.inflate(handle, buffers.readable(in, n), n, NetBuffers.writable(inflated, size), size);
			if (made < 0) throw new DecoderException("Badly compressed packet - invalid compressed data");
			if (made != size)
				throw new DecoderException("Badly compressed packet - actual length of uncompressed payload " + made + " is does not match declared size " + size);
			inflated.writerIndex(inflated.writerIndex() + size);
			in.skipBytes(n);
		} catch (RuntimeException | Error e) {
			inflated.release();
			throw e;
		}
		FastNet.INFLATED.addAndGet(size);
		FastNet.PACKETS.incrementAndGet();
		out.add(inflated);
	}

	@Override
	protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
		super.handlerRemoved0(ctx);
		if (handle != 0) NetNative.inflaterFree(handle);
		handle = 0;
	}
}
