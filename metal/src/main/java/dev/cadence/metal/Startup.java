// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Opt-in startup changes, each behaviour-identical: the same objects and values, made later or concurrently.
 * <ul>
 * <li>{@code -Dcadence.startup.narrator=lazy}: the macOS speech channel (0.47 s on the MacBook Neo) is created on the narrator's first
 * use instead of in Minecraft's constructor.</li>
 * <li>{@code -Dcadence.startup.crashPreload=async}: the warm-up crash report (its hardware probe runs an external command, 0.43 s on the
 * Neo) is built on a daemon thread; the memory reserve is still allocated first, on the main thread.</li>
 * <li>{@code -Dcadence.startup.blockCache=parallel}: every block state's cache (shapes, light, sturdy faces) is computed in parallel
 * after the registry loop, which keeps adding the states to the id map in order. {@code =digest} computes them as vanilla does and
 * prints a digest of every cache; {@code =parallel-digest} prints it for the parallel path, so the two can be compared.</li>
 * <li>{@code -Dcadence.startup.fadeMs=N}: the loading overlay's fade-out takes N ms instead of 1000 (fade-in N/2 instead of 500);
 * a visible UI change, not a game-logic one.</li>
 * </ul>
 */
public final class Startup {
	public static final String NARRATOR = System.getProperty("cadence.startup.narrator", "");
	public static final String CRASH_PRELOAD = System.getProperty("cadence.startup.crashPreload", "");
	public static final String BLOCK_CACHE = System.getProperty("cadence.startup.blockCache", "");
	public static final float FADE_MS = Float.parseFloat(System.getProperty("cadence.startup.fadeMs", "-1"));

	private static final List<BlockState> PENDING = new ArrayList<>();

	private Startup() {
	}

	public static boolean parallelBlockCache() {
		return BLOCK_CACHE.startsWith("parallel");
	}

	/** Collects a state whose cache the registry loop would have built now. */
	public static void deferInitCache(BlockState state) {
		PENDING.add(state);
	}

	/** At the end of the registry loop: the deferred caches, in parallel, before Blocks' static initialisation returns. */
	public static void finishBlockCaches() {
		long t0 = System.nanoTime();
		if (parallelBlockCache()) {
			ForkJoinPool pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
			try {
				pool.submit(() -> PENDING.parallelStream().forEach(BlockState::initCache)).join();
			} finally {
				pool.shutdown();
			}
		}
		long t1 = System.nanoTime();
		if (BLOCK_CACHE.endsWith("digest")) {
			System.out.printf("cadence-startup: block caches %s, %d states, %.1f ms, digest %016x%n", BLOCK_CACHE, PENDING.isEmpty() ? -1 : PENDING.size(),
				(t1 - t0) / 1e6, digest());
		} else if (parallelBlockCache()) {
			System.out.printf("cadence-startup: block caches in parallel, %d states, %.1f ms%n", PENDING.size(), (t1 - t0) / 1e6);
		}
		PENDING.clear();
	}

	/** Every field initCache sets, for every state in id order: a value digest (shapes by their boxes, arrays element-wise). */
	private static long digest() {
		long h = 1125899906842597L;
		try {
			Class<?> base = BlockState.class.getSuperclass();
			List<Field> fields = new ArrayList<>();
			for (Field f : base.getDeclaredFields()) {
				// only what initCache sets: the non-final instance fields (the final ones come from the constructor)
				if (!Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers())) {
					f.setAccessible(true);
					fields.add(f);
				}
			}
			String dump = System.getProperty("cadence.startup.blockCacheDump");
			java.io.PrintWriter out = dump == null ? null : new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(java.nio.file.Path.of(dump)));
			int i = 0;
			for (BlockState s : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY) {
				for (Field f : fields) {
					long v = value(f.get(s), 0);
					h = mix(h, v);
					if (out != null) out.printf("%d %s %s %016x%n", i, s, f.getName(), v);
				}
				i++;
			}
			// registry order (numeric ids) of blocks and items: Items' initialisation can move to a cache worker
			for (var b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
				h = mix(h, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).hashCode());
				if (out != null) out.println("block " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b));
			}
			for (var it : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				h = mix(h, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).hashCode());
				if (out != null) out.println("item " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it));
			}
			if (out != null) out.close();
		} catch (ReflectiveOperationException | java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		return h;
	}

	private static long value(Object v, int depth) throws IllegalAccessException {
		if (v == null) return 0;
		if (v instanceof VoxelShape shape) return shape.toAabbs().hashCode();
		if (v.getClass().isArray()) {
			long h = 7;
			for (int i = 0; i < Array.getLength(v); i++) h = mix(h, value(Array.get(v, i), depth + 1));
			return h;
		}
		if (v instanceof Enum<?> e) return e.name().hashCode();
		if (v instanceof Number || v instanceof Boolean || v instanceof String) return v.hashCode();
		if (v.getClass().getName().endsWith("$Cache") && depth == 0) {
			long h = 11;
			for (Field f : v.getClass().getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) continue;
				f.setAccessible(true);
				h = mix(h, value(f.get(v), depth + 1));
			}
			return h;
		}
		// (by its fluid's id, amount and source flag: its toString holds the fluid's identity hash, different every run)
		if (v instanceof net.minecraft.world.level.material.FluidState fs)
			return mix(mix(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fs.getType()).hashCode(), fs.getAmount()), fs.isSource() ? 1 : 0);
		throw new IllegalStateException("block cache digest: unhandled value type " + v.getClass().getName());
	}

	private static long mix(long h, long v) {
		h ^= v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
		return h * 0xff51afd7ed558ccdL;
	}
}
