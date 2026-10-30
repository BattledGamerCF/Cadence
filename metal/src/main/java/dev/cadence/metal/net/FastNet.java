package dev.cadence.metal.net;

import java.security.Key;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import net.minecraft.network.CipherDecoder;
import net.minecraft.network.CompressionDecoder;

/**
 * Cadence: the network path for remote servers. A connection to a server (or a LAN guest on the integrated server) has two
 * byte-stream stages Minecraft runs in Java on Netty's threads: AES-128-CFB8 decryption (online-mode servers) and zlib
 * decompression of every packet over the server's threshold (chunks, mostly). Both are replaced here, with the same bytes out:
 * <ul>
 * <li>decryption: CFB8 makes each byte from an AES block of the 16 ciphertext bytes before it, all known when decrypting, so
 * mcnet.c runs 8 bytes' AES at once on the ARMv8 cryptography instructions: ~1,000 MB/s against Java's ~120 (M2);</li>
 * <li>decompression: the system's zlib, 2.3x faster than the JDK's bundled copy (18.6 against 43.4 us per chunk).</li>
 * </ul>
 * Singleplayer never gets here: its connection is in memory, with neither stage. -Dcadence.net=false keeps Minecraft's own;
 * it stays off with another networking mod (NetMixinPlugin). A stage that can't start (no native handle) falls back to
 * Minecraft's for that connection.
 */
public final class FastNet {
	public static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("cadence.net", "true"));
	/** Decrypting ciphers Crypt.getCipher made, with their key (Minecraft's IV is the key): the decoder made next takes it. */
	private static final Map<Cipher, byte[]> KEYS = Collections.synchronizedMap(new WeakHashMap<>());
	static final AtomicLong DECRYPTED = new AtomicLong(), INFLATED = new AtomicLong(), PACKETS = new AtomicLong();
	private static volatile boolean announced;

	private FastNet() {
	}

	/** Crypt.getCipher RETURN. */
	public static void rememberKey(int mode, Cipher cipher, Key key) {
		if (!ON || mode != Cipher.DECRYPT_MODE || cipher == null || key == null) return;
		byte[] k = key.getEncoded();
		if (k != null && k.length == 16) KEYS.put(cipher, k);
	}

	/** Connection.setEncryptionKey: the decrypting stage. */
	public static CipherDecoder decoder(Cipher cipher) {
		byte[] key = ON ? KEYS.remove(cipher) : null;
		if (key != null) {
			try {
				CipherDecoder d = new FastDecrypt(cipher, key);
				announce();
				return d;
			} catch (RuntimeException | LinkageError e) {
				System.out.println("[cadence] Network: native decryption unavailable (" + e + "); Minecraft's own for this connection");
			}
		}
		return new CipherDecoder(cipher);
	}

	/** Connection.setupCompression: the decompressing stage. */
	public static CompressionDecoder decompressor(int threshold, boolean validate) {
		if (ON) {
			try {
				CompressionDecoder d = new FastInflate(threshold, validate);
				announce();
				return d;
			} catch (RuntimeException | LinkageError e) {
				System.out.println("[cadence] Network: native decompression unavailable (" + e + "); Minecraft's own for this connection");
			}
		}
		return new CompressionDecoder(threshold, validate);
	}

	private static void announce() {
		if (announced) return;
		announced = true;
		System.out.println("[cadence] Network: native decryption and decompression for remote connections");
	}

	/** Totals since launch (self-test): bytes decrypted, bytes inflated, packets inflated. */
	public static long[] totals() {
		return new long[] {DECRYPTED.get(), INFLATED.get(), PACKETS.get()};
	}
}
