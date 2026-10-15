package dev.cadence.shaders.cadence.facecull;

import com.mojang.blaze3d.vertex.MeshData;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import org.slf4j.LoggerFactory;

/**
 * Cadence: per-section back-face group culling (FaceGroups) — the build-time reorder and the draw-time split.
 * -Dcadence.faceCull=false: vanilla (one draw per section layer, every quad). Switchable at runtime for A/B (draw side;
 * meshes built while on stay grouped, which draws identically).
 */
public final class FaceCull {
	public static volatile boolean enabled = !"false".equals(System.getProperty("cadence.faceCull"));
	/** Draw-time reads from the mesh's packed array (FaceGroups.pack); -Dcadence.faceCullPacked=false: the map and objects. */
	public static volatile boolean packedGroups = !"false".equals(System.getProperty("cadence.faceCullPacked"));
	private static final boolean VERIFY = Boolean.getBoolean("cadence.meshCacheVerify");
	private static long verified, verifyMismatches;
	private static final boolean LOG = Boolean.getBoolean("cadence.probe") || System.getProperty("cadence.selftest") != null;

	/** Shadow pass: the direction toward the light (draws are built for an orthographic view along it); null: the camera. */
	private static double[] towardLight;
	private static long draws, quadsAll, quadsDrawn, logged;

	private FaceCull() {
	}

	/** SectionCompiler.compile RETURN (worker): group the opaque/cutout layers' quads. */
	public static Map<ChunkSectionLayer, FaceGroups> build(Map<ChunkSectionLayer, MeshData> layers) {
		if (!enabled) return null;
		EnumMap<ChunkSectionLayer, FaceGroups> out = null;
		for (var e : layers.entrySet()) {
			ChunkSectionLayer layer = e.getKey();
			MeshData mesh = e.getValue();
			if (layer.translucent() || mesh == null || mesh.indexBuffer() != null) continue;
			var state = mesh.drawState();
			FaceGroups g = FaceGroups.reorder(mesh.vertexBuffer(), state.format().getVertexSize(), state.vertexCount());
			if (g == null) continue;
			if (out == null) out = new EnumMap<>(ChunkSectionLayer.class);
			out.put(layer, g);
		}
		return out;
	}

	/** ShadowRenderer: draws prepared next are for the light (toward = the direction toward the light), until cleared. */
	public static void shadowView(double x, double y, double z) {
		towardLight = new double[] {x, y, z};
	}

	public static void cameraView() {
		towardLight = null;
	}

	/** Draws being prepared now are the shadow pass's. */
	public static boolean preparingShadow() {
		return towardLight != null;
	}

	/**
	 * LevelRenderer.extractSectionDrawGroups, in place of adding {@code draw} for one section layer: the draws of the
	 * groups that can face the viewer (adjacent ones merged), or the vanilla draw.
	 */
	// per frame (the camera position object changes every frame): camera and each layer's back-face culling
	private static Object frameKey;
	private static double camX, camY, camZ;
	private static final boolean[] layerCulls = new boolean[ChunkSectionLayer.values().length];
	/** Below this share of a section's quads facing away, one draw costs less than the split (each command is a CPU call). */
	private static final int MIN_SAVED_PERCENT = 15;

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void addDraws(List list, DynamicGpuData.IndexedDraw draw, FaceGroups groups, BlockPos origin, ChunkSectionLayer layer) {
		add(list, null, draw, draw.indexCount(), draw.firstIndex(), draw.baseVertex(), draw.baseInstance(), groups, origin, layer);
	}

	/** The same for a draw PackedDraws stashed (its ints, no record): written straight into the packed list. */
	public static void addPacked(dev.cadence.shaders.cadence.alloc.PackedDrawList list, FaceGroups groups, BlockPos origin, ChunkSectionLayer layer) {
		int indexCount = dev.cadence.shaders.cadence.alloc.PackedDraws.indexCount(), instances = dev.cadence.shaders.cadence.alloc.PackedDraws.instanceCount(),
			firstIndex = dev.cadence.shaders.cadence.alloc.PackedDraws.firstIndex(), baseVertex = dev.cadence.shaders.cadence.alloc.PackedDraws.baseVertex(),
			baseInstance = dev.cadence.shaders.cadence.alloc.PackedDraws.baseInstance();
		dev.cadence.shaders.cadence.alloc.PackedDraws.taken();
		if (instances != 1) {
			// vanilla's section draws are single-instance; anything else is written as it came
			list.add(indexCount, instances, firstIndex, baseVertex, baseInstance);
			return;
		}
		add(null, list, null, indexCount, firstIndex, baseVertex, baseInstance, groups, origin, layer);
	}

	/**
	 * {@link #addPacked(dev.cadence.shaders.cadence.alloc.PackedDrawList, FaceGroups, BlockPos, ChunkSectionLayer)} reading the
	 * mesh's packed groups ({@code data} from FaceGroups.pack, this layer at {@code base}; null or an empty slot: no groups).
	 * The same decisions on the same numbers. {@code groups} (the map) is only read by -Dcadence.meshCacheVerify.
	 */
	public static void addPacked(dev.cadence.shaders.cadence.alloc.PackedDrawList list, int[] data, int base,
		java.util.Map<ChunkSectionLayer, FaceGroups> groups, BlockPos origin, ChunkSectionLayer layer) {
		int indexCount = dev.cadence.shaders.cadence.alloc.PackedDraws.indexCount(), instances = dev.cadence.shaders.cadence.alloc.PackedDraws.instanceCount(),
			firstIndex = dev.cadence.shaders.cadence.alloc.PackedDraws.firstIndex(), baseVertex = dev.cadence.shaders.cadence.alloc.PackedDraws.baseVertex(),
			baseInstance = dev.cadence.shaders.cadence.alloc.PackedDraws.baseInstance();
		dev.cadence.shaders.cadence.alloc.PackedDraws.taken();
		if (instances != 1) {
			list.add(indexCount, instances, firstIndex, baseVertex, baseInstance);
			return;
		}
		boolean has = data != null && data[base + FaceGroups.STRIDE - 1] != 0;
		if (!enabled || !has || layer.translucent() || indexCount != data[base + FaceGroups.COUNT] * 6) {
			list.add(indexCount, 1, firstIndex, baseVertex, baseInstance);
			return;
		}
		var pos = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		if (pos != frameKey) {
			frameKey = pos;
			camX = pos.x;
			camY = pos.y;
			camZ = pos.z;
			for (ChunkSectionLayer l : ChunkSectionLayer.values()) layerCulls[l.ordinal()] = !l.translucent() && l.pipeline(true).isCull();
		}
		if (!layerCulls[layer.ordinal()]) {
			list.add(indexCount, 1, firstIndex, baseVertex, baseInstance);
			return;
		}
		int mask;
		double[] light = towardLight;
		if (light != null) mask = FaceGroups.visibleMask(data, base, light[0], light[1], light[2], true);
		else mask = FaceGroups.visibleMask(data, base, camX - origin.getX(), camY - origin.getY(), camZ - origin.getZ(), false);
		if (VERIFY) verify(data, base, groups, layer, mask, light, origin);
		int total = data[base + FaceGroups.COUNT], kept = 0;
		for (int g = 0; g < FaceGroups.COUNT; g++) if ((mask & 1 << g) != 0) kept += data[base + g + 1] - data[base + g];
		quadsAll += total;
		if ((total - kept) * 100 < total * MIN_SAVED_PERCENT) {
			list.add(indexCount, 1, firstIndex, baseVertex, baseInstance);
			quadsDrawn += total;
			draws++;
			return;
		}
		int runStart = -1;
		boolean emitted = false;
		for (int g = 0; g <= FaceGroups.COUNT; g++) {
			int s0 = data[base + g], s1 = g < FaceGroups.COUNT ? data[base + g + 1] : s0;
			boolean on = g < FaceGroups.COUNT && (mask & 1 << g) != 0 && s1 > s0;
			if (on && runStart < 0) runStart = s0;
			boolean empty = g < FaceGroups.COUNT && s1 == s0;
			if (!on && !empty && runStart >= 0 || g == FaceGroups.COUNT && runStart >= 0) {
				int end = s0;
				list.add((end - runStart) * 6, 1, firstIndex + runStart * 6, baseVertex, baseInstance);
				quadsDrawn += end - runStart;
				draws++;
				runStart = -1;
				emitted = true;
			}
		}
		if (!emitted) list.add(0, 1, firstIndex, baseVertex, baseInstance);
	}

	/** -Dcadence.meshCacheVerify: the packed numbers and mask against the groups they were packed from. */
	private static void verify(int[] data, int base, java.util.Map<ChunkSectionLayer, FaceGroups> groups, ChunkSectionLayer layer, int mask,
		double[] light, BlockPos origin) {
		FaceGroups g = groups == null ? null : groups.get(layer);
		boolean ok = g != null;
		if (ok) for (int i = 0; i <= FaceGroups.COUNT; i++) ok &= g.start[i] == data[base + i];
		if (ok) {
			int expected = light != null ? g.visibleMask(light[0], light[1], light[2], true)
				: g.visibleMask(camX - origin.getX(), camY - origin.getY(), camZ - origin.getZ(), false);
			ok = expected == mask;
		}
		verified++;
		if (!ok) verifyMismatches++;
		if ((verified & 0xfffff) == 0) LoggerFactory.getLogger("cadence").info("Face groups verify: {} packed draws checked, {} mismatches",
			verified, verifyMismatches);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void whole(List list, dev.cadence.shaders.cadence.alloc.PackedDrawList packed, DynamicGpuData.IndexedDraw draw, int indexCount,
		int firstIndex, int baseVertex, int baseInstance) {
		if (packed != null) packed.add(indexCount, 1, firstIndex, baseVertex, baseInstance);
		else list.add(draw);
	}

	/** One draw (into {@code list}, or {@code packed} when given), or its face-group runs. {@code draw}: the record, if any. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void add(List list, dev.cadence.shaders.cadence.alloc.PackedDrawList packed, DynamicGpuData.IndexedDraw draw, int indexCount,
		int firstIndex, int baseVertex, int baseInstance, FaceGroups groups, BlockPos origin, ChunkSectionLayer layer) {
		if (!enabled || groups == null || layer.translucent() || indexCount != groups.start[FaceGroups.COUNT] * 6) {
			whole(list, packed, draw, indexCount, firstIndex, baseVertex, baseInstance);
			return;
		}
		var pos = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		if (pos != frameKey) {
			frameKey = pos;
			camX = pos.x;
			camY = pos.y;
			camZ = pos.z;
			for (ChunkSectionLayer l : ChunkSectionLayer.values()) layerCulls[l.ordinal()] = !l.translucent() && l.pipeline(true).isCull();
		}
		if (!layerCulls[layer.ordinal()]) {
			whole(list, packed, draw, indexCount, firstIndex, baseVertex, baseInstance);
			return;
		}
		int mask;
		double[] light = towardLight;
		if (light != null) mask = groups.visibleMask(light[0], light[1], light[2], true);
		else mask = groups.visibleMask(camX - origin.getX(), camY - origin.getY(), camZ - origin.getZ(), false);
		int total = groups.start[FaceGroups.COUNT], kept = 0;
		for (int g = 0; g < FaceGroups.COUNT; g++) if ((mask & 1 << g) != 0) kept += groups.start[g + 1] - groups.start[g];
		quadsAll += total;
		if ((total - kept) * 100 < total * MIN_SAVED_PERCENT) {
			whole(list, packed, draw, indexCount, firstIndex, baseVertex, baseInstance);
			quadsDrawn += total;
			draws++;
			return;
		}
		int runStart = -1;
		boolean emitted = false;
		for (int g = 0; g <= FaceGroups.COUNT; g++) {
			boolean on = g < FaceGroups.COUNT && (mask & 1 << g) != 0 && groups.start[g + 1] > groups.start[g];
			if (on && runStart < 0) runStart = groups.start[g];
			boolean empty = g < FaceGroups.COUNT && groups.start[g + 1] == groups.start[g];
			if (!on && !empty && runStart >= 0 || g == FaceGroups.COUNT && runStart >= 0) {
				int end = groups.start[g];
				if (packed != null) packed.add((end - runStart) * 6, 1, firstIndex + runStart * 6, baseVertex, baseInstance);
				else list.add(new DynamicGpuData.IndexedDraw((end - runStart) * 6, 1, firstIndex + runStart * 6, baseVertex, baseInstance));
				quadsDrawn += end - runStart;
				draws++;
				runStart = -1;
				emitted = true;
			}
		}
		if (!emitted) {
			// every face turned away: one empty draw (the GPU skips it), because vanilla's command writer expects every
			// draw group it was given a list for to hold at least one draw (an empty one crashed it)
			if (packed != null) packed.add(0, 1, firstIndex, baseVertex, baseInstance);
			else list.add(new DynamicGpuData.IndexedDraw(0, 1, firstIndex, baseVertex, baseInstance));
		}
		if (LOG && (draws & 0xffff) == 0 && System.nanoTime() - logged > 30_000_000_000L) {
			logged = System.nanoTime();
			LoggerFactory.getLogger("cadence").info("Face groups: {} of {} grouped quads drawn ({}%), {} draws",
				quadsDrawn, quadsAll, quadsAll == 0 ? 0 : quadsDrawn * 100 / quadsAll, draws);
		}
	}
}
