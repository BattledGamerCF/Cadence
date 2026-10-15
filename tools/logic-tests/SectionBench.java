import dev.cadence.metal.logic.EntitySectionScan;
import it.unimi.dsi.fastutil.longs.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.phys.AABB;

public class SectionBench {
	static int sink;
	public static void main(String[] a) throws Exception {
		Random r = new Random(7);
		Class<?> fake = Proxy.getProxyClass(EntityAccess.class.getClassLoader(), EntityAccess.class);
		Constructor<?> ctor = fake.getConstructor(InvocationHandler.class);
		InvocationHandler h = (p, m, args) -> m.getName().equals("hashCode") ? System.identityHashCode(p) : null;
		@SuppressWarnings("unchecked")
		EntitySectionStorage<EntityAccess> storage = new EntitySectionStorage<>((Class<EntityAccess>) fake, k -> Visibility.TICKING);
		// RD 12: 25x25 chunks; entity sections exist where entities have been: ground level (y -1..5) everywhere
		for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = -1; y <= 5; y++) {
			EntitySection<EntityAccess> s = storage.getOrCreateSection(SectionPos.asLong(x, y, z));
			if (r.nextInt(3) == 0) s.add((EntityAccess) ctor.newInstance(h));
		}
		Field sf = EntitySectionStorage.class.getDeclaredField("sections"); sf.setAccessible(true);
		@SuppressWarnings("unchecked") Long2ObjectMap<EntitySection<EntityAccess>> sections = (Long2ObjectMap<EntitySection<EntityAccess>>) sf.get(storage);
		int n = 200_000; AABB[] boxes = new AABB[n];
		for (int i = 0; i < n; i++) { double x = (r.nextDouble() - .5) * 360, y = 60 + r.nextDouble() * 20, z = (r.nextDouble() - .5) * 360; boxes[i] = new AABB(x, y, z, x + 0.6, y + 1.8, z + 0.6); }
		AbortableIterationConsumer<EntitySection<EntityAccess>> c = s -> { sink++; return net.minecraft.util.Continuation.CONTINUE; };
		for (int round = 0; round < 8; round++) {
			long t0 = System.nanoTime();
			for (AABB b : boxes) storage.forEachAccessibleNonEmptySection(b, c);
			long t1 = System.nanoTime();
			for (AABB b : boxes) {
				int minX = SectionPos.posToSectionCoord(b.minX - 2.0), minY = SectionPos.posToSectionCoord(b.minY - 4.0), minZ = SectionPos.posToSectionCoord(b.minZ - 2.0);
				int maxX = SectionPos.posToSectionCoord(b.maxX + 2.0), maxY = SectionPos.posToSectionCoord(b.maxY), maxZ = SectionPos.posToSectionCoord(b.maxZ + 2.0);
				EntitySectionScan.scan(sections, minX, maxX, minY, maxY, minZ, maxZ, c);
			}
			long t2 = System.nanoTime();
			if (round >= 4) System.out.printf("mob-sized search: vanilla %.0f ns, Cadence %.0f ns (%.1fx)%n", (t1 - t0) / (double) n, (t2 - t1) / (double) n, (t1 - t0) / (double) (t2 - t1));
		}
	}
}
