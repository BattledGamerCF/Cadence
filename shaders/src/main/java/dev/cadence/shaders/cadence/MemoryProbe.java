package dev.cadence.shaders.cadence;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * Cadence diagnostics: where the chunk geometry memory is. Per layer, the vertex and index uber buffers' heaps (each heap is
 * allocated whole: 128 MB vertex, 32 MB index) and the bytes actually allocated in them; plus the Metal device's total.
 */
final class MemoryProbe {
	private MemoryProbe() {
	}

	static String summary(SectionRenderDispatcher dispatcher) {
		StringBuilder out = new StringBuilder();
		long gpu = dev.cadence.metal.GpuMemory.allocatedBytes();
		out.append(String.format(Locale.ROOT, "metal %.0f MB", gpu / 1048576.0));
		if (dispatcher == null) return out.toString();
		try {
			Field mapField = SectionRenderDispatcher.class.getDeclaredField("chunkUberBuffers");
			mapField.setAccessible(true);
			Map<?, ?> map = (Map<?, ?>) mapField.get(dispatcher);
			Class<?> record = Class.forName("net.minecraft.client.renderer.chunk.SectionRenderDispatcher$SectionUberBuffers");
			Method vertexOf = record.getDeclaredMethod("vertexBuffer"), indexOf = record.getDeclaredMethod("indexBuffer");
			vertexOf.setAccessible(true);
			indexOf.setAccessible(true);
			long heaps = 0, used = 0;
			for (var e : map.entrySet()) {
				long[] v = stats((UberGpuBuffer<?>) vertexOf.invoke(e.getValue())), i = stats((UberGpuBuffer<?>) indexOf.invoke(e.getValue()));
				heaps += v[0] + i[0];
				used += v[1] + i[1];
				out.append(String.format(Locale.ROOT, " | %s v %d heaps %.0f/%.0f MB i %d heaps %.0f/%.0f MB", e.getKey(), v[2], v[1] / 1048576.0,
					v[0] / 1048576.0, i[2], i[1] / 1048576.0, i[0] / 1048576.0));
			}
			out.append(String.format(Locale.ROOT, " | chunk buffers %.0f MB reserved, %.0f MB used", heaps / 1048576.0, used / 1048576.0));
		} catch (ReflectiveOperationException | RuntimeException e) {
			out.append(" | uber stats unavailable: ").append(e);
		}
		return out.toString();
	}

	/**
	 * Where the chunk geometry is: bytes (index count / 6 * 4 vertices * stride) of compiled sections, split by whether the
	 * camera's visible list has the section and whether the section is wholly below the terrain surface (its top under the
	 * lowest WORLD_SURFACE height of its 4 corner columns and centre). Plus the Java heap.
	 */
	static String census(net.minecraft.client.Minecraft mc) {
		var renderer = mc.levelRenderer;
		var level = mc.level;
		Runtime rt = Runtime.getRuntime();
		String heap = String.format(Locale.ROOT, "heap used %.0f / committed %.0f / max %.0f MB", (rt.totalMemory() - rt.freeMemory()) / 1048576.0,
			rt.totalMemory() / 1048576.0, rt.maxMemory() / 1048576.0);
		if (renderer.viewArea() == null || level == null) return heap;
		try {
			Field f = net.minecraft.client.renderer.ViewArea.class.getDeclaredField("sections");
			f.setAccessible(true);
			Iterable<?> sections = (Iterable<?>) f.get(renderer.viewArea());
			var visible = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
			visible.addAll(renderer.visibleSections());
			long[] bytes = new long[4]; // visible-surface, visible-under, shadow-surface, shadow-under
			int[] count = new int[4];
			for (Object o : sections) {
				var section = (SectionRenderDispatcher.RenderSection) o;
				var mesh = section.getSectionMesh();
				if (mesh == null) continue;
				long b = 0;
				for (var layer : net.minecraft.client.renderer.chunk.ChunkSectionLayer.values()) {
					var draw = mesh.getSectionDraw(layer);
					if (draw != null) b += (long) draw.indexCount() / 6 * 4 * layer.vertexFormat().getVertexSize();
				}
				if (b == 0) continue;
				var origin = section.getRenderOrigin();
				int x = origin.getX(), z = origin.getZ();
				int surface = Math.min(Math.min(Math.min(height(level, x, z), height(level, x + 15, z)), Math.min(height(level, x, z + 15), height(level, x + 15, z + 15))),
					height(level, x + 8, z + 8));
				boolean under = origin.getY() + 16 <= surface;
				int k = (visible.contains(section) ? 0 : 2) + (under ? 1 : 0);
				bytes[k] += b;
				count[k]++;
			}
			// Light reachability (diagnostic): which shadow-only sections no sun ray can reach through open faces.
			var runtime = dev.cadence.shaders.runtime.ShaderRuntime.get();
			String reach = "";
			if (runtime != null && runtime.uniforms() != null) {
				var dir = runtime.uniforms().shadowDirectionWorld();
				boolean[] needed = LightReach.needed(renderer.viewArea(), level, -dir.x(), -dir.y(), -dir.z());
				var receivers = ReceiverDepth.receivers(renderer, level, cameraFrustum(mc), sections);
				var depth = new ReceiverDepth(new org.joml.Vector3f(-dir.x(), -dir.y(), -dir.z()), receivers);
				long[] q = new long[2];
				int[] qc = new int[2];
				long[] r = new long[2];
				int[] rc = new int[2];
				var area = renderer.viewArea();
				for (Object o : sections) {
					var section = (SectionRenderDispatcher.RenderSection) o;
					if (visible.contains(section)) continue;
					var mesh = section.getSectionMesh();
					if (mesh == null) continue;
					long b = 0;
					for (var layer : net.minecraft.client.renderer.chunk.ChunkSectionLayer.values()) {
						var draw = mesh.getSectionDraw(layer);
						if (draw != null) b += (long) draw.indexCount() / 6 * 4 * layer.vertexFormat().getVertexSize();
					}
					if (b == 0) continue;
					int k = LightReach.isNeeded(needed, area, section.getRenderOrigin()) ? 0 : 1;
					r[k] += b;
					rc[k]++;
					int m = depth.matters(section.getRenderOrigin()) ? 0 : 1;
					q[m] += b;
					qc[m]++;
				}
				reach = String.format(Locale.ROOT, " | light-reach (sun dir %.2f %.2f %.2f): shadow-only sections a sun ray can reach %.0f MB (%d), cannot %.0f MB (%d)",
					dir.x(), dir.y(), dir.z(), r[0] / 1048576.0, rc[0], r[1] / 1048576.0, rc[1])
					+ String.format(Locale.ROOT, " | receiver-depth (%d receivers): shadow-only sections that can shadow a receiver %.0f MB (%d), cannot %.0f MB (%d)",
					receivers.size(), q[0] / 1048576.0, qc[0], q[1] / 1048576.0, qc[1]);
			}
			return heap + String.format(Locale.ROOT, " | geometry: on-screen surface %.0f MB (%d), on-screen underground %.0f MB (%d), shadow-only surface %.0f MB (%d), shadow-only underground %.0f MB (%d)",
				bytes[0] / 1048576.0, count[0], bytes[1] / 1048576.0, count[1], bytes[2] / 1048576.0, count[2], bytes[3] / 1048576.0, count[3]) + reach;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return heap + " | census unavailable: " + e;
		}
	}

	private static net.minecraft.client.renderer.culling.Frustum cameraFrustum(net.minecraft.client.Minecraft mc) {
		return mc.gameRenderer.mainCamera().getCullFrustum();
	}

	private static int height(net.minecraft.world.level.Level level, int x, int z) {
		return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
	}

	/** {reserved bytes, allocated bytes, heap count} */
	private static long[] stats(UberGpuBuffer<?> buffer) throws ReflectiveOperationException {
		Field nodesField = UberGpuBuffer.class.getDeclaredField("nodes"), heapField = UberGpuBuffer.class.getDeclaredField("heapSize"),
			allocField = UberGpuBuffer.class.getDeclaredField("allocationMap");
		nodesField.setAccessible(true);
		heapField.setAccessible(true);
		allocField.setAccessible(true);
		int heaps = ((List<?>) nodesField.get(buffer)).size();
		long reserved = (long) heaps * heapField.getInt(buffer), used = 0;
		Method size = null;
		for (Object a : ((Map<?, ?>) allocField.get(buffer)).values()) {
			if (size == null) size = a.getClass().getMethod("getSize");
			used += (long) size.invoke(a);
		}
		return new long[] {reserved, used, heaps};
	}
}
