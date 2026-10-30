package dev.cadence.metal.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import javax.crypto.Cipher;
import net.minecraft.network.CipherDecoder;

/** Minecraft's decrypting stage (CipherDecoder) on mcnet.c's parallel AES-CFB8 (FastNet); the cipher it was given goes unused. */
final class FastDecrypt extends CipherDecoder {
	private final NetBuffers buffers = new NetBuffers();
	private long handle;

	FastDecrypt(Cipher cipher, byte[] key) {
		super(cipher);
		try (Arena a = Arena.ofConfined()) {
			MemorySegment k = a.allocate(16);
			k.copyFrom(MemorySegment.ofArray(key));
			handle = NetNative.cfb8New(k.address());
		}
		if (handle == 0) throw new IllegalStateException("no native cipher");
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		int n = in.readableBytes();
		ByteBuf plain = ctx.alloc().directBuffer(n);
		try {
			if (n > 0) NetNative.cfb8Decrypt(handle, buffers.readable(in, n), NetBuffers.writable(plain, n), n);
			plain.writerIndex(plain.writerIndex() + n);
			in.skipBytes(n);
		} catch (RuntimeException | Error e) {
			plain.release();
			throw e;
		}
		FastNet.DECRYPTED.addAndGet(n);
		out.add(plain);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		super.handlerRemoved(ctx);
		if (handle != 0) NetNative.cfb8Free(handle);
		handle = 0;
	}
}
