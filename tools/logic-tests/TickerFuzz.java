import java.util.*;
public class TickerFuzz {
	static final class T { final int id; boolean removed; int[] kills = {}; boolean tickable = true; T(int id) { this.id = id; } }
	static List<Integer> run(List<T> list, boolean ours, boolean normal) {
		List<Integer> log = new ArrayList<>();
		if (!ours) {
			Iterator<T> it = list.iterator();
			while (it.hasNext()) { T t = it.next(); if (t.removed) { it.remove(); continue; } if (normal && t.tickable) tick(t, list, log); }
		} else {
			int size = list.size(), next = 0, kept = 0;
			try {
				while (next < size) { T t = list.get(next++); if (t.removed) continue; if (kept != next - 1) list.set(kept, t); kept++; if (normal && t.tickable) tick(t, list, log); }
			} finally { if (kept != next) { for (int i = next; i < size; i++) list.set(kept++, list.get(i)); list.subList(kept, size).clear(); } }
		}
		return log;
	}
	static void tick(T t, List<T> all, List<Integer> log) { log.add(t.id); for (int k : t.kills) for (T o : all) if (o.id == k) o.removed = true; }
	public static void main(String[] a) {
		Random r = new Random(3); int cases = 0;
		for (int c = 0; c < 200000; c++) {
			int n = r.nextInt(40); List<T> va = new ArrayList<>(), ou = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				boolean rem = r.nextInt(3) == 0, tk = r.nextInt(5) != 0; int[] kills = r.nextInt(6) == 0 ? new int[] {r.nextInt(Math.max(n, 1))} : new int[0];
				T x = new T(i); x.removed = rem; x.tickable = tk; x.kills = kills; va.add(x);
				T y = new T(i); y.removed = rem; y.tickable = tk; y.kills = kills; ou.add(y);
			}
			boolean normal = r.nextInt(8) != 0;
			List<Integer> lv = run(va, false, normal), lo = run(ou, true, normal);
			List<Integer> fv = va.stream().map(t -> t.id).toList(), fo = ou.stream().map(t -> t.id).toList();
			if (!lv.equals(lo) || !fv.equals(fo)) { System.out.println("MISMATCH case " + c + " ticks " + lv + " vs " + lo + " lists " + fv + " vs " + fo); System.exit(1); }
			cases++;
		}
		System.out.println("OK: " + cases + " random ticker lists, same ticks in the same order and the same list after");
	}
}
