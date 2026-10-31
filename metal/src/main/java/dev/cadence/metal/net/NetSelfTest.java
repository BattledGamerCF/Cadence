package dev.cadence.metal.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import net.minecraft.network.CipherDecoder;
import net.minecraft.network.CipherEncoder;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Crypt;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Self-test step "net": Minecraft's decrypting and decompressing stages against Cadence's native ones (FastNet), in Netty
 * channels of their own: the same stream through both, compared byte for byte, then timed. Payloads are the loaded world's
 * chunk sections in the chunk packet's own format (what a server's chunk packets mostly carry) plus random packets.
 */
public final class NetSelfTest {
	private NetSelfTest() {
	}

	/** The chunks' sections in the chunk packet's format (render thread: the client's chunks are written as they stand). */
	public static List<byte[]> payloads(List<LevelChunk> chunks) {
		List<byte[]> payloads = new ArrayList<>();
		for (LevelChunk chunk : chunks) {
			FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
			for (LevelChunkSection s : chunk.getSections()) s.write(b);
			byte[] bytes = new byte[b.readableBytes()];
			b.readBytes(bytes);
			b.release();
			payloads.add(bytes);
		}
		return payloads;
	}

	/** One line of results (or what differed); call off the render thread. */
	public static String run(List<byte[]> payloads) throws Exception {
		Random r = new Random(42);
		for (int i = 0; i < 400; i++) {  // small packets: movement, entity data (some below the threshold, sent as they are)
			byte[] p = new byte[1 + r.nextInt(i % 4 == 0 ? 4000 : 300)];
			for (int k = 0; k < p.length; k++) p[k] = (byte) (r.nextInt(8) == 0 ? r.nextInt() : k % 7);
			payloads.add(p);
		}
		StringBuilder out = new StringBuilder();
		out.append(compression(payloads)).append(" | ").append(decryption(payloads, r));
		return out.toString();
	}

	private static String compression(List<byte[]> payloads) {
		EmbeddedChannel enc = new EmbeddedChannel(new CompressionEncoder(256));
		List<ByteBuf> frames = new ArrayList<>();
		long raw = 0;
		for (byte[] p : payloads) {
			enc.writeOutbound(Unpooled.wrappedBuffer(p));
			ByteBuf f = enc.readOutbound();
			frames.add(f);
			raw += p.length;
		}
		try {  // (the frames released on every way out, BH10-11)
			// correctness: every frame through both
			List<byte[]> vanilla = decodeAll(new CompressionDecoder(256, true), frames, 1);
			CompressionDecoder fast = FastNet.decompressor(256, true);
			if (!(fast instanceof FastInflate)) return "decompression: native stage unavailable";
			List<byte[]> ours = decodeAll(fast, frames, 1);
			for (int i = 0; i < vanilla.size(); i++)
				if (!java.util.Arrays.equals(vanilla.get(i), ours.get(i))) return "decompression DIFFERS at packet " + i;
			if (vanilla.size() != payloads.size()) return "decompression: " + vanilla.size() + " of " + payloads.size() + " packets";
			long tv = time(new CompressionDecoder(256, true), frames, 20), tn = time(FastNet.decompressor(256, true), frames, 20);
			return String.format(Locale.ROOT, "decompression identical (%d packets, %.1f MB), Minecraft %.1f us vs native %.1f us per packet (%.2fx)",
				payloads.size(), raw / 1e6, tv / 1e3 / payloads.size() / 20, tn / 1e3 / payloads.size() / 20, (double) tv / tn);
		} finally {
			for (ByteBuf f : frames) f.release();
		}
	}

	private static String decryption(List<byte[]> payloads, Random r) throws Exception {
		KeyGenerator g = KeyGenerator.getInstance("AES");
		g.init(128);
		SecretKey key = g.generateKey();
		// the server's side: one encrypted stream of all payloads
		EmbeddedChannel enc = new EmbeddedChannel(new CipherEncoder(Crypt.getCipher(Cipher.ENCRYPT_MODE, key)));
		ByteBuf stream = Unpooled.buffer();
		for (byte[] p : payloads) {
			enc.writeOutbound(Unpooled.wrappedBuffer(p));
			ByteBuf e = enc.readOutbound();
			stream.writeBytes(e);
			e.release();
		}
		// cut as TCP might: pieces of 1 byte to 64 KB
		List<ByteBuf> pieces = new ArrayList<>();
		while (stream.isReadable()) {
			int n = Math.min(stream.readableBytes(), r.nextInt(4) == 0 ? 1 + r.nextInt(40) : 1 + r.nextInt(65536));
			pieces.add(stream.readRetainedSlice(n));
		}
		CipherDecoder fast = FastNet.decoder(Crypt.getCipher(Cipher.DECRYPT_MODE, key));
		if (!(fast instanceof FastDecrypt)) return "decryption: native stage unavailable";
		byte[] vanilla = concat(decodeAll(new CipherDecoder(Crypt.getCipher(Cipher.DECRYPT_MODE, key)), pieces, 1));
		byte[] ours = concat(decodeAll(fast, pieces, 1));
		if (!java.util.Arrays.equals(vanilla, ours)) return "decryption DIFFERS";
		long bytes = vanilla.length;
		// timing: a fresh decoder per round (the stream's state), large reads as a busy server sends
		List<ByteBuf> big = new ArrayList<>();
		stream.readerIndex(0);
		while (stream.isReadable()) big.add(stream.readRetainedSlice(Math.min(stream.readableBytes(), 65536)));
		long tv = 0, tn = 0;
		for (int round = 0; round < 10; round++) {
			tv += time(new CipherDecoder(Crypt.getCipher(Cipher.DECRYPT_MODE, key)), big, 1);
			tn += time(FastNet.decoder(Crypt.getCipher(Cipher.DECRYPT_MODE, key)), big, 1);
		}
		for (ByteBuf p : pieces) p.release();
		for (ByteBuf p : big) p.release();
		stream.release();
		return String.format(Locale.ROOT, "decryption identical (%.1f MB in %d pieces), Minecraft %.0f MB/s vs native %.0f MB/s (%.1fx)",
			bytes / 1e6, pieces.size(), bytes * 10 / (tv / 1e3), bytes * 10 / (tn / 1e3), (double) tv / tn);
	}

	private static List<byte[]> decodeAll(ChannelHandler decoder, List<ByteBuf> inputs, int rounds) {
		EmbeddedChannel ch = new EmbeddedChannel(decoder);
		List<byte[]> result = new ArrayList<>();
		for (ByteBuf in : inputs) {
			ch.writeInbound(in.retainedDuplicate());
			ByteBuf b;
			while ((b = ch.readInbound()) != null) {
				byte[] bytes = new byte[b.readableBytes()];
				b.readBytes(bytes);
				b.release();
				result.add(bytes);
			}
		}
		ch.finishAndReleaseAll();
		return result;
	}

	/** ns for rounds passes of inputs through decoder (a CipherDecoder: one pass, its stream state can't restart). */
	private static long time(ChannelHandler decoder, List<ByteBuf> inputs, int rounds) {
		EmbeddedChannel ch = new EmbeddedChannel(decoder);
		long t = System.nanoTime();
		for (int k = 0; k < rounds; k++) {
			for (ByteBuf in : inputs) {
				ch.writeInbound(in.retainedDuplicate());
				ByteBuf b;
				while ((b = ch.readInbound()) != null) b.release();
			}
		}
		t = System.nanoTime() - t;
		ch.finishAndReleaseAll();
		return t;
	}

	private static byte[] concat(List<byte[]> parts) {
		int n = 0;
		for (byte[] p : parts) n += p.length;
		byte[] all = new byte[n];
		int at = 0;
		for (byte[] p : parts) {
			System.arraycopy(p, 0, all, at, p.length);
			at += p.length;
		}
		return all;
	}
}
