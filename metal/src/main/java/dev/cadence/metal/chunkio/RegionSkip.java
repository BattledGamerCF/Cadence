// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.chunkio;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * C2: is a chunk write a no-op? Vanilla re-saves every chunk it loads (SerializableChunkData.read and the loading pyramid's LIGHT
 * step call setLightCorrect/setAllStarts/setAllReferences, which mark the chunk unsaved), so most writes during play put back the
 * bytes the region file already holds with a new LastUpdate stamp. The game never reads LastUpdate back (SerializableChunkData
 * parses it into a record field that only write() uses), nor the region header's per-chunk timestamps.
 *
 * <p>The check runs where the write would run (RegionFileStorage.write, on the storage's one IO thread, after every earlier
 * write of this chunk has reached the file): the new tag is serialized exactly as vanilla serializes it for the compressor, the
 * stored stream is decompressed with the region file's own reader, and the two byte strings are compared. They count as equal
 * only if they are byte-identical outside the 8-byte value of the root compound's LastUpdate long (found by walking the NBT
 * structure, not by searching bytes). Anything else (a missing or unreadable stored chunk, a different length, a different key
 * order, any parse doubt) means "write as vanilla does". Equal bytes decode to equal NBT, so a skipped write cannot change what
 * any later load sees.
 */
public final class RegionSkip {
	private RegionSkip() { }

	public enum Result { IDENTICAL, LAST_UPDATE_ONLY, DIFFERENT, NO_STORED }

	/** Reusable buffers per IO thread (a storage's writes are serialized on one consecutive executor). */
	private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);

	public static final class Buffers {
		final FastDataOutput fresh = new FastDataOutput(1 << 17);
		byte[] stored = new byte[1 << 17];
		public int storedLength;
		final byte[] chunk = new byte[1 << 14];
	}

	/** Serialize {@code value} as NbtIo.write does for the region stream (no compression). */
	public static Buffers serialize(CompoundTag value) throws IOException {
		Buffers b = BUFFERS.get();
		b.fresh.reset();
		NbtIo.write(value, b.fresh);
		return b;
	}

	/**
	 * Compare the decompressed stored stream with the serialized new tag while reading it, stopping at the first difference
	 * outside the LastUpdate value. The fresh bytes' root LastUpdate offset is found structurally; stored bytes before it are
	 * equal to the fresh ones whenever the scan gets there, so the same offset is the stored root's LastUpdate value too.
	 */
	public static Result compareStream(Buffers b, InputStream in) throws IOException {
		byte[] fresh = b.fresh.array();
		int lf = b.fresh.length(), pc = lastUpdateOffset(fresh, lf), pos = 0;
		byte[] chunk = b.chunk;
		boolean lastUpdateDiffers = false;
		b.storedLength = 0;
		while (true) {
			int n = in.read(chunk, 0, chunk.length);
			if (n < 0) break;
			if (n == 0) continue;
			if (pos + n > lf) return Result.DIFFERENT;
			int m = Arrays.mismatch(chunk, 0, n, fresh, pos, pos + n);
			while (m >= 0) {
				int at = pos + m;
				if (pc < 0 || at < pc || at >= pc + 8) return Result.DIFFERENT;
				lastUpdateDiffers = true;
				int resume = Math.min(pc + 8 - pos, n);
				int m2 = Arrays.mismatch(chunk, resume, n, fresh, pos + resume, pos + n);
				m = m2 < 0 ? -1 : resume + m2;
			}
			pos += n;
			b.storedLength = pos;
		}
		if (pos != lf) return Result.DIFFERENT;
		return lastUpdateDiffers ? Result.LAST_UPDATE_ONLY : Result.IDENTICAL;
	}

	/** Read the whole decompressed stored stream into the thread's buffer. */
	public static void readStored(Buffers b, InputStream in) throws IOException {
		int n = 0;
		while (true) {
			if (n == b.stored.length) b.stored = Arrays.copyOf(b.stored, b.stored.length * 2);
			int r = in.read(b.stored, n, b.stored.length - n);
			if (r < 0) break;
			n += r;
		}
		b.storedLength = n;
	}

	public static Result compare(Buffers b) {
		byte[] a = b.stored, c = b.fresh.array();
		int la = b.storedLength, lc = b.fresh.length();
		int m = Arrays.mismatch(a, 0, la, c, 0, lc);
		if (m < 0) return Result.IDENTICAL;
		if (la != lc) return Result.DIFFERENT;
		int pa = lastUpdateOffset(a, la), pc = lastUpdateOffset(c, lc);
		if (pa < 0 || pa != pc || m < pa || m >= pa + 8) return Result.DIFFERENT;
		return Arrays.equals(a, pa + 8, la, c, pc + 8, lc) ? Result.LAST_UPDATE_ONLY : Result.DIFFERENT;
	}

	private static final byte[] LAST_UPDATE = "LastUpdate".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

	/** Offset of the 8 value bytes of the root compound's "LastUpdate" long, or -1 if absent or the bytes don't parse. */
	static int lastUpdateOffset(byte[] b, int len) {
		try {
			int p = 0;
			if (len < 3 || b[p++] != 10) return -1;
			p += 2 + u16(b, p, len);
			while (p < len) {
				int type = b[p++] & 0xFF;
				if (type == 0) return -1;
				int nameLength = u16(b, p, len);
				p += 2;
				boolean match = type == 4 && nameLength == LAST_UPDATE.length && p + nameLength <= len
					&& Arrays.equals(b, p, p + nameLength, LAST_UPDATE, 0, nameLength);
				p += nameLength;
				if (match) return p + 8 <= len ? p : -1;
				p = skip(b, p, len, type, 0);
				if (p < 0) return -1;
			}
			return -1;
		} catch (IndexOutOfBoundsException e) {
			return -1;
		}
	}

	private static int skip(byte[] b, int p, int len, int type, int depth) {
		if (depth > 512) return -1;
		long q;
		switch (type) {
			case 1 -> q = p + 1L;
			case 2 -> q = p + 2L;
			case 3, 5 -> q = p + 4L;
			case 4, 6 -> q = p + 8L;
			case 7 -> { int n = i32(b, p, len); q = n < 0 ? -1 : p + 4L + n; }
			case 8 -> q = p + 2L + u16(b, p, len);
			case 9 -> {
				int elementType = b[p] & 0xFF;
				int n = i32(b, p + 1, len);
				if (n < 0 || (elementType == 0 && n > 0)) return -1;
				p += 5;
				for (int i = 0; i < n; i++) {
					p = skip(b, p, len, elementType, depth + 1);
					if (p < 0) return -1;
				}
				q = p;
			}
			case 10 -> {
				while (true) {
					if (p >= len) return -1;
					int t = b[p++] & 0xFF;
					if (t == 0) break;
					p += 2 + u16(b, p, len);
					p = skip(b, p, len, t, depth + 1);
					if (p < 0) return -1;
				}
				q = p;
			}
			case 11 -> { int n = i32(b, p, len); q = n < 0 ? -1 : p + 4L + 4L * n; }
			case 12 -> { int n = i32(b, p, len); q = n < 0 ? -1 : p + 4L + 8L * n; }
			default -> q = -1;
		}
		return q < 0 || q > len ? -1 : (int) q;
	}

	private static int u16(byte[] b, int p, int len) {
		if (p + 2 > len) throw new IndexOutOfBoundsException();
		return (b[p] & 0xFF) << 8 | b[p + 1] & 0xFF;
	}

	private static int i32(byte[] b, int p, int len) {
		if (p + 4 > len) throw new IndexOutOfBoundsException();
		return b[p] << 24 | (b[p + 1] & 0xFF) << 16 | (b[p + 2] & 0xFF) << 8 | b[p + 3] & 0xFF;
	}

	/** Probe only: which root keys (and section sub-keys) differ between the stored and the new tag. */
	public static Set<String> diffKeys(Buffers b, CompoundTag fresh) {
		Set<String> out = new TreeSet<>();
		try {
			CompoundTag stored = NbtIo.read(new DataInputStream(new ByteArrayInputStream(b.stored, 0, b.storedLength)));
			Set<String> keys = new TreeSet<>(stored.keySet());
			keys.addAll(fresh.keySet());
			for (String k : keys) {
				Tag x = stored.get(k), y = fresh.get(k);
				if (java.util.Objects.equals(x, y)) continue;
				if (k.equals("sections") && x instanceof ListTag lx && y instanceof ListTag ly && lx.size() == ly.size()) {
					for (int i = 0; i < lx.size(); i++) {
						if (lx.get(i) instanceof CompoundTag sx && ly.get(i) instanceof CompoundTag sy) {
							Set<String> sk = new TreeSet<>(sx.keySet());
							sk.addAll(sy.keySet());
							for (String s : sk) if (!java.util.Objects.equals(sx.get(s), sy.get(s))) out.add("sections." + s);
						} else {
							out.add("sections.?");
						}
					}
				} else {
					out.add(k);
				}
			}
			if (out.isEmpty()) out.add("(order only)");
		} catch (IOException | RuntimeException e) {
			out.add("(unparsable)");
		}
		return out;
	}
}
