package dev.cadence.shaders.cadence.memory;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import net.minecraft.client.gui.font.CodepointMap;
import net.minecraft.client.gui.font.providers.UnihexProvider;
import org.slf4j.LoggerFactory;

/**
 * Cadence: the Unicode fallback font (unifont, ~136,000 glyphs) kept as one byte array instead of a glyph object, a
 * contents object and a small array per glyph (~88 bytes each, ~12 MB, for characters most players never draw).
 *
 * <p>Each glyph's 16 lines are stored at their own width (1, 2 or 4 bytes per line, as vanilla's Byte/Short/IntContents),
 * with its left/right bounds; getGlyph builds vanilla's Glyph record around a small LineData view of those bytes, which
 * returns exactly the line values vanilla's contents objects return. Same pixels, ~6 MB less heap.
 * -Dcadence.compactUnifont=false: vanilla.
 */
public final class UnihexCompact {
	public static final boolean ENABLED = !"false".equals(System.getProperty("cadence.compactUnifont"));
	private static final MethodHandle NEW_GLYPH, CONTENTS, LEFT, RIGHT;

	static {
		MethodHandle n = null, c = null, l = null, r = null;
		try {
			Class<?> glyph = Class.forName("net.minecraft.client.gui.font.providers.UnihexProvider$Glyph", false, UnihexProvider.class.getClassLoader());
			var lookup = MethodHandles.privateLookupIn(glyph, MethodHandles.lookup());
			n = lookup.findConstructor(glyph, MethodType.methodType(void.class, UnihexProvider.LineData.class, int.class, int.class));
			c = lookup.findVirtual(glyph, "contents", MethodType.methodType(UnihexProvider.LineData.class));
			l = lookup.findVirtual(glyph, "left", MethodType.methodType(int.class));
			r = lookup.findVirtual(glyph, "right", MethodType.methodType(int.class));
		} catch (ReflectiveOperationException | RuntimeException e) {
			LoggerFactory.getLogger("cadence").info("Compact unifont unavailable ({}); vanilla glyphs kept", e.toString());
		}
		NEW_GLYPH = n;
		CONTENTS = c;
		LEFT = l;
		RIGHT = r;
	}

	private final int[] codepoints, offsets;
	private final byte[] bytesPerLine, left, right, data;

	private UnihexCompact(int[] codepoints, int[] offsets, byte[] bytesPerLine, byte[] left, byte[] right, byte[] data) {
		this.codepoints = codepoints;
		this.offsets = offsets;
		this.bytesPerLine = bytesPerLine;
		this.left = left;
		this.right = right;
		this.data = data;
	}

	/** Packs the provider's glyphs (and empties its map); null when not possible (vanilla glyphs stay). */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static UnihexCompact pack(CodepointMap glyphs) {
		if (!ENABLED || NEW_GLYPH == null) return null;
		try {
			IntSet keys = glyphs.keySet();
			int[] cps = keys.toIntArray();
			Arrays.sort(cps);
			int n = cps.length;
			int[] offsets = new int[n];
			byte[] widths = new byte[n], left = new byte[n], right = new byte[n];
			int total = 0;
			for (int i = 0; i < n; i++) {
				Object g = glyphs.get(cps[i]);
				UnihexProvider.LineData lines = (UnihexProvider.LineData) CONTENTS.invoke(g);
				int bits = lines.bitWidth();
				widths[i] = (byte) (bits <= 8 ? 1 : bits <= 16 ? 2 : 4);
				left[i] = (byte) (int) LEFT.invoke(g);
				right[i] = (byte) (int) RIGHT.invoke(g);
				offsets[i] = total;
				total += 16 * widths[i] + (widths[i] == 4 ? 1 : 0); // 4-byte lines keep their exact bit width (24 or 32)
			}
			byte[] data = new byte[total];
			for (int i = 0; i < n; i++) {
				UnihexProvider.LineData lines = (UnihexProvider.LineData) CONTENTS.invoke(glyphs.get(cps[i]));
				int o = offsets[i], w = widths[i];
				for (int line = 0; line < 16; line++) {
					int value = lines.line(line);
					switch (w) {
						case 1 -> data[o + line] = (byte) (value >>> 24);
						case 2 -> {
							data[o + 2 * line] = (byte) (value >>> 24);
							data[o + 2 * line + 1] = (byte) (value >>> 16);
						}
						default -> {
							data[o + 4 * line] = (byte) (value >>> 24);
							data[o + 4 * line + 1] = (byte) (value >>> 16);
							data[o + 4 * line + 2] = (byte) (value >>> 8);
							data[o + 4 * line + 3] = (byte) value;
						}
					}
				}
				if (w == 4) data[o + 64] = (byte) lines.bitWidth();
			}
			glyphs.clear();
			return new UnihexCompact(cps, offsets, widths, left, right, data);
		} catch (Throwable t) {
			LoggerFactory.getLogger("cadence").info("Compact unifont failed ({}); vanilla glyphs kept", t.toString());
			return null;
		}
	}

	/** vanilla's Glyph for the codepoint, or null if the font has none. */
	public Object glyph(int codepoint) {
		int i = Arrays.binarySearch(codepoints, codepoint);
		if (i < 0) return null;
		try {
			return NEW_GLYPH.invoke(new Lines(data, offsets[i], bytesPerLine[i]), (int) left[i], (int) right[i]);
		} catch (Throwable t) {
			throw new IllegalStateException("Compact unifont glyph", t);
		}
	}

	public IntSet supported() {
		return new IntOpenHashSet(codepoints);
	}

	/** A glyph's lines, read as vanilla's Byte/Short/IntContents return them. */
	private record Lines(byte[] data, int offset, int bytes) implements UnihexProvider.LineData {
		@Override
		public int line(int index) {
			int o = offset + index * bytes;
			return switch (bytes) {
				case 1 -> data[o] << 24;
				case 2 -> (data[o] & 0xff) << 24 | (data[o + 1] & 0xff) << 16;
				default -> (data[o] & 0xff) << 24 | (data[o + 1] & 0xff) << 16 | (data[o + 2] & 0xff) << 8 | data[o + 3] & 0xff;
			};
		}

		@Override
		public int bitWidth() {
			return bytes == 4 ? data[offset + 64] : bytes * 8;
		}
	}
}
