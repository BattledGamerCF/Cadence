package dev.cadence.shaders.cadence.alloc;

import java.nio.ByteBuffer;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.DynamicGpuDataStorage;

/**
 * Cadence (PackedDraws): a section draw group's indirect commands as packed ints (5 per draw, the order
 * DynamicGpuData.IndexedDraw.write puts them in) instead of one IndexedDraw object each. Vanilla's command writer only
 * iterates a list and calls write(ByteBuffer) on each element, so iteration hands out one reused writer per list; get()
 * and getLast() return real IndexedDraw records for anything else. Render thread; recycled by PackedDraws.
 */
public final class PackedDrawList extends AbstractList<Object> implements RandomAccess {
	private int[] data = new int[5 * 64];
	private int size;
	private final Writer writer = new Writer();

	public void add(int indexCount, int instanceCount, int firstIndex, int baseVertex, int baseInstance) {
		int at = size * 5;
		if (at + 5 > data.length) data = Arrays.copyOf(data, data.length * 2);
		data[at] = indexCount;
		data[at + 1] = instanceCount;
		data[at + 2] = firstIndex;
		data[at + 3] = baseVertex;
		data[at + 4] = baseInstance;
		size++;
		modCount++;
	}

	@Override
	public boolean add(Object element) {
		if (element == PackedDraws.SENTINEL) PackedDraws.addStashed(this);
		else if (element instanceof DynamicGpuData.IndexedDraw d)
			add(d.indexCount(), d.instanceCount(), d.firstIndex(), d.baseVertex(), d.baseInstance());
		else throw new IllegalArgumentException("Not an indexed draw: " + element);
		return true;
	}

	@Override
	public Object get(int index) {
		if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
		int at = index * 5;
		return new DynamicGpuData.IndexedDraw(data[at], data[at + 1], data[at + 2], data[at + 3], data[at + 4]);
	}

	@Override
	public int size() {
		return size;
	}

	@Override
	public void clear() {
		size = 0;
		modCount++;
	}

	/** One writer, moved along the list: each element is written before the next is asked for (vanilla's writer). */
	@Override
	public Iterator<Object> iterator() {
		writer.next = 0;
		return writer;
	}

	private final class Writer implements Iterator<Object>, DynamicGpuDataStorage.DynamicGpuData {
		int next, at;

		@Override
		public boolean hasNext() {
			return next < size;
		}

		@Override
		public Object next() {
			if (next >= size) throw new NoSuchElementException();
			at = next++ * 5;
			return this;
		}

		@Override
		public void write(ByteBuffer buffer) {
			buffer.putInt(data[at]).putInt(data[at + 1]).putInt(data[at + 2]).putInt(data[at + 3]).putInt(data[at + 4]);
		}
	}
}
