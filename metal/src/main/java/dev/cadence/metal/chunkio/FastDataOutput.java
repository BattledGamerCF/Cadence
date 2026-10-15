// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.chunkio;

import java.io.DataOutput;
import java.io.UTFDataFormatException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A growable big-endian DataOutput over one byte array, with no locking: DataOutputStream over ByteArrayOutputStream takes a
 * monitor for every writeInt/writeLong (thousands per chunk). Writes exactly the bytes DataOutputStream writes, including
 * writeUTF's modified UTF-8 and its UTFDataFormatException (thrown before anything is written) above 65535 encoded bytes.
 */
public final class FastDataOutput implements DataOutput {
	private static final VarHandle SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
	private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
	private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
	private byte[] buf;
	private int count;

	public FastDataOutput(int size) {
		this.buf = new byte[size];
	}

	public byte[] array() { return this.buf; }
	public int length() { return this.count; }
	public void reset() { this.count = 0; }

	private void ensure(int more) {
		if (this.count + more > this.buf.length) this.buf = Arrays.copyOf(this.buf, Math.max(this.buf.length * 2, this.count + more));
	}

	@Override public void write(int b) { ensure(1); this.buf[this.count++] = (byte) b; }
	@Override public void write(byte[] b) { write(b, 0, b.length); }
	@Override public void write(byte[] b, int off, int len) { ensure(len); System.arraycopy(b, off, this.buf, this.count, len); this.count += len; }
	@Override public void writeBoolean(boolean v) { write(v ? 1 : 0); }
	@Override public void writeByte(int v) { write(v); }
	@Override public void writeShort(int v) { ensure(2); SHORT.set(this.buf, this.count, (short) v); this.count += 2; }
	@Override public void writeChar(int v) { writeShort(v); }
	@Override public void writeInt(int v) { ensure(4); INT.set(this.buf, this.count, v); this.count += 4; }
	@Override public void writeLong(long v) { ensure(8); LONG.set(this.buf, this.count, v); this.count += 8; }
	@Override public void writeFloat(float v) { writeInt(Float.floatToIntBits(v)); }
	@Override public void writeDouble(double v) { writeLong(Double.doubleToLongBits(v)); }

	@Override
	public void writeBytes(String s) {
		int len = s.length();
		ensure(len);
		for (int i = 0; i < len; i++) this.buf[this.count++] = (byte) s.charAt(i);
	}

	@Override
	public void writeChars(String s) {
		int len = s.length();
		for (int i = 0; i < len; i++) writeShort(s.charAt(i));
	}

	@Override
	public void writeUTF(String s) throws UTFDataFormatException {
		int len = s.length();
		long utf = len;
		for (int i = 0; i < len; i++) {
			int c = s.charAt(i);
			if (c >= 0x80 || c == 0) utf += c >= 0x800 ? 2 : 1;
		}
		if (utf > 65535) throw new UTFDataFormatException("encoded string too long: " + utf + " bytes");
		ensure(2 + (int) utf);
		SHORT.set(this.buf, this.count, (short) utf);
		int p = this.count + 2;
		for (int i = 0; i < len; i++) {
			int c = s.charAt(i);
			if (c < 0x80 && c != 0) {
				this.buf[p++] = (byte) c;
			} else if (c < 0x800) {
				this.buf[p++] = (byte) (0xC0 | c >> 6 & 0x1F);
				this.buf[p++] = (byte) (0x80 | c & 0x3F);
			} else {
				this.buf[p++] = (byte) (0xE0 | c >> 12 & 0x0F);
				this.buf[p++] = (byte) (0x80 | c >> 6 & 0x3F);
				this.buf[p++] = (byte) (0x80 | c & 0x3F);
			}
		}
		this.count = p;
	}
}
