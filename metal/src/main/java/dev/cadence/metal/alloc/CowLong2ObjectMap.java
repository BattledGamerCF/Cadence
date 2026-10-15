// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.alloc;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectSet;

/**
 * A long-keyed map whose {@link #clone()} costs O(buckets), not O(entries): the entries live in {@value #BUCKETS} sub-maps by key
 * hash; a clone shares them, and whichever map writes a shared sub-map first copies it (copy-on-write). A map that only reads,
 * like the light engine's published snapshot, never copies anything, and a shared sub-map is never modified, so a published
 * snapshot is as immutable as the plain clone it replaces.
 *
 * <p>It extends {@link Long2ObjectOpenHashMap} only to fit the field it replaces ({@code DataLayerStorageMap.map}); the superclass's
 * own storage stays empty and every method that could touch it is overridden here.
 */
public final class CowLong2ObjectMap<V> extends Long2ObjectOpenHashMap<V> {
	static final int BUCKETS = 256;
	private final Long2ObjectOpenHashMap<V>[] buckets;
	/** owned[i]: buckets[i] belongs to this map alone and may be written in place. */
	private final boolean[] owned;
	private int size;

	@SuppressWarnings("unchecked")
	public CowLong2ObjectMap() {
		super(0);
		buckets = new Long2ObjectOpenHashMap[BUCKETS];
		owned = new boolean[BUCKETS];
	}

	public static <V> CowLong2ObjectMap<V> of(Long2ObjectMap<V> from) {
		CowLong2ObjectMap<V> m = new CowLong2ObjectMap<>();
		for (Long2ObjectMap.Entry<V> e : from.long2ObjectEntrySet()) m.put(e.getLongKey(), e.getValue());
		return m;
	}

	private CowLong2ObjectMap(CowLong2ObjectMap<V> from) {
		super(0);
		buckets = from.buckets.clone();
		owned = new boolean[BUCKETS];
		java.util.Arrays.fill(from.owned, false);
		size = from.size;
	}

	private static int index(long key) {
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h >>> 56);
	}

	private Long2ObjectOpenHashMap<V> writable(int i) {
		Long2ObjectOpenHashMap<V> b = buckets[i];
		if (b == null) {
			b = new Long2ObjectOpenHashMap<>(8);
			buckets[i] = b;
			owned[i] = true;
		} else if (!owned[i]) {
			b = b.clone();
			buckets[i] = b;
			owned[i] = true;
		}
		return b;
	}

	@Override public V get(long key) { Long2ObjectOpenHashMap<V> b = buckets[index(key)]; return b == null ? defRetValue : b.get(key); }
	@Override public boolean containsKey(long key) { Long2ObjectOpenHashMap<V> b = buckets[index(key)]; return b != null && b.containsKey(key); }
	@Override public V put(long key, V value) {
		Long2ObjectOpenHashMap<V> b = writable(index(key));
		int before = b.size();
		V old = b.put(key, value);
		size += b.size() - before;
		return old == null ? defRetValue : old;
	}
	@Override public V remove(long key) {
		int i = index(key);
		if (buckets[i] == null || !buckets[i].containsKey(key)) return defRetValue;
		Long2ObjectOpenHashMap<V> b = writable(i);
		size--;
		return b.remove(key);
	}
	@Override public int size() { return size; }
	@Override public boolean isEmpty() { return size == 0; }
	@Override public void clear() { java.util.Arrays.fill(buckets, null); java.util.Arrays.fill(owned, false); size = 0; }
	@Override public CowLong2ObjectMap<V> clone() { return new CowLong2ObjectMap<>(this); }
	@Override public V getOrDefault(long key, V d) { Long2ObjectOpenHashMap<V> b = buckets[index(key)]; return b == null ? d : b.getOrDefault(key, d); }
	@Override public V putIfAbsent(long key, V value) { V v = get(key); if (v == null && !containsKey(key)) { put(key, value); return defRetValue; } return v; }
	@Override public V computeIfAbsent(long key, java.util.function.LongFunction<? extends V> f) {
		if (containsKey(key)) return get(key);
		V v = f.apply(key);
		if (v != null) put(key, v);
		return v;
	}
	@Override public V replace(long key, V value) { return containsKey(key) ? put(key, value) : defRetValue; }
	@Override public boolean remove(long key, Object value) { if (containsKey(key) && java.util.Objects.equals(get(key), value)) { remove(key); return true; } return false; }
	@Override public void putAll(java.util.Map<? extends Long, ? extends V> m) { for (var e : m.entrySet()) put(e.getKey().longValue(), e.getValue()); }
	@Override public boolean containsValue(Object v) { for (var b : buckets) if (b != null && b.containsValue(v)) return true; return false; }

	/** A merged, detached copy: iteration isn't on the light engine's paths; anything that iterates gets a correct snapshot. */
	private Long2ObjectOpenHashMap<V> merged() {
		Long2ObjectOpenHashMap<V> m = new Long2ObjectOpenHashMap<>(size);
		for (var b : buckets) if (b != null) m.putAll(b);
		return m;
	}
	@Override public FastEntrySet<V> long2ObjectEntrySet() { return merged().long2ObjectEntrySet(); }
	@Override public LongSet keySet() { return merged().keySet(); }
	@Override public ObjectCollection<V> values() { return merged().values(); }
	@Override public ObjectSet<java.util.Map.Entry<Long, V>> entrySet() { return merged().entrySet(); }
	@Override public void forEach(java.util.function.BiConsumer<? super Long, ? super V> action) { merged().forEach(action); }
	@Override public boolean trim() { return true; }
	@Override public boolean trim(int n) { return true; }
	@Override public int hashCode() { return merged().hashCode(); }
	@Override public boolean equals(Object o) { return merged().equals(o); }
	@Override public String toString() { return merged().toString(); }
}
