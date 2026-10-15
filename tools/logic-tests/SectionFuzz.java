import dev.cadence.metal.logic.EntitySectionScan;
import it.unimi.dsi.fastutil.longs.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.phys.AABB;

public class SectionFuzz {
	public static void main(String[] a) throws Exception {
		Random r = new Random(42);
		Class<?> fake = Proxy.getProxyClass(EntityAccess.class.getClassLoader(), EntityAccess.class);
		Constructor<?> fakeCtor = fake.getConstructor(InvocationHandler.class);
		InvocationHandler h = (p, m, args) -> m.getName().equals("hashCode") ? System.identityHashCode(p) : m.getName().equals("equals") ? p == args[0] : null;
		Visibility[] vis = Visibility.values();
		long cases = 0, fast = 0, slow = 0, visits = 0;
		for (int world = 0; world < 300; world++) {
			Map<Long, Visibility> initial = new HashMap<>();
			@SuppressWarnings("unchecked")
			EntitySectionStorage<EntityAccess> storage = new EntitySectionStorage<>((Class<EntityAccess>) fake, key -> initial.getOrDefault(key, vis[r.nextInt(vis.length)]));
			int span = 2 + r.nextInt(12), ySpan = 2 + r.nextInt(8);
			int cx = r.nextInt(5) - 2, cz = r.nextInt(5) - 2, cy = r.nextInt(5) - 2;
			List<Long> keys = new ArrayList<>();
			for (int n = 0; n < 200 + r.nextInt(1500); n++) {
				int x = cx + r.nextInt(2 * span + 1) - span, y = cy + r.nextInt(2 * ySpan + 1) - ySpan, z = cz + r.nextInt(2 * span + 1) - span;
				long key = SectionPos.asLong(x, y, z);
				EntitySection<EntityAccess> s = storage.getOrCreateSection(key);
				keys.add(key);
				int count = r.nextInt(4) == 0 ? 0 : 1 + r.nextInt(3);
				for (int e = 0; e < count; e++) s.add((EntityAccess) fakeCtor.newInstance(h));
				if (r.nextInt(8) == 0) s.updateChunkStatus(vis[r.nextInt(vis.length)]);
			}
			for (int n = 0; n < keys.size() / 10; n++) storage.remove(keys.get(r.nextInt(keys.size())));
			Field sf = EntitySectionStorage.class.getDeclaredField("sections"); sf.setAccessible(true);
			Field idf = EntitySectionStorage.class.getDeclaredField("sectionIds"); idf.setAccessible(true);
			@SuppressWarnings("unchecked") Long2ObjectMap<EntitySection<EntityAccess>> sections = (Long2ObjectMap<EntitySection<EntityAccess>>) sf.get(storage);
			LongSortedSet ids = (LongSortedSet) idf.get(storage);
			for (int q = 0; q < 3000; q++) {
				double size = r.nextInt(20) == 0 ? 2000 + r.nextDouble() * 6000 : r.nextInt(10) == 0 ? r.nextDouble() * 400 : r.nextDouble() * 40;
				double x0 = (cx * 16) + (r.nextDouble() - 0.5) * span * 32 - (size > 1000 ? size / 2 : 0), y0 = (cy * 16) + (r.nextDouble() - 0.5) * ySpan * 40, z0 = (cz * 16) + (r.nextDouble() - 0.5) * span * 32 - (size > 1000 ? size / 2 : 0);
				AABB box = new AABB(x0, y0, z0, x0 + size * r.nextDouble(), y0 + size * r.nextDouble(), z0 + size * r.nextDouble());
				int abortAt = r.nextInt(4) == 0 ? r.nextInt(6) : Integer.MAX_VALUE;
				List<Object> want = new ArrayList<>(), got = new ArrayList<>();
				storage.forEachAccessibleNonEmptySection(box, rec(want, abortAt));
				int minX = SectionPos.posToSectionCoord(box.minX - 2.0), minY = SectionPos.posToSectionCoord(box.minY - 4.0), minZ = SectionPos.posToSectionCoord(box.minZ - 2.0);
				int maxX = SectionPos.posToSectionCoord(box.maxX + 2.0), maxY = SectionPos.posToSectionCoord(box.maxY + 0.0), maxZ = SectionPos.posToSectionCoord(box.maxZ + 2.0);
				if (EntitySectionScan.handles(minY, maxY, minZ, maxZ)) { fast++; EntitySectionScan.scan(sections, minX, maxX, minY, maxY, minZ, maxZ, rec(got, abortAt)); }
				else { slow++; fallback(sections, ids, minX, maxX, minY, maxY, minZ, maxZ, rec(got, abortAt)); }
				cases++; visits += want.size();
				if (!want.equals(got)) { System.out.println("MISMATCH world " + world + " box " + box + " want " + want.size() + " got " + got.size()); System.exit(1); }
			}
		}
		System.out.printf("OK: %d boxes (%d fast path, %d fallback), %d section visits, all identical%n", cases, fast, slow, visits);
	}
	static AbortableIterationConsumer<EntitySection<EntityAccess>> rec(List<Object> out, int abortAt) {
		return s -> { out.add(s); return out.size() >= abortAt ? net.minecraft.util.Continuation.ABORT : net.minecraft.util.Continuation.CONTINUE; };
	}
	static void fallback(Long2ObjectMap<EntitySection<EntityAccess>> sections, LongSortedSet ids, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, AbortableIterationConsumer<EntitySection<EntityAccess>> consumer) {
		for (int x = minX; x <= maxX; x++) {
			LongIterator it = ids.subSet(SectionPos.asLong(x, 0, 0), SectionPos.asLong(x, -1, -1) + 1L).iterator();
			while (it.hasNext()) {
				long key = it.nextLong(); int y = SectionPos.y(key), z = SectionPos.z(key);
				if (y < minY || y > maxY || z < minZ || z > maxZ) continue;
				EntitySection<EntityAccess> s = sections.get(key);
				if (s != null && !s.isEmpty() && s.getStatus().isAccessible() && consumer.accept(s).shouldAbort()) return;
			}
		}
	}
}
