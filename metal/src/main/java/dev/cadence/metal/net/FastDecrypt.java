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
	private final NativeHandle handle;

	FastDecrypt(Cipher cipher, byte[] key) {
		super(cipher);
		try (Arena a = Arena.ofConfined()) {
			MemorySegment k = a.allocate(16);
			k.copyFrom(MemorySegment.ofArray(key));
			long h = NetNative.cfb8New(k.address());
			if (h == 0) throw new IllegalStateException("no native cipher");
			handle = NativeHandle.of(this, h, NetNative::cfb8Free);
		}
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		int n = in.readableBytes();
		ByteBuf plain = ctx.alloc().directBuffer(n);
		try {
			long src = n > 0 ? buffers.readable(in, n) : 0;
			in.skipBytes(n);
			if (n > 0) NetNative.cfb8Decrypt(handle.get(), src, NetBuffers.writable(plain, n), n);
			plain.writerIndex(plain.writerIndex() + n);
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
		handle.free();
	}
}
