package dev.cadence.shaders.cadence.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

/**
 * Cadence: far shadow casters drawn as one coarse mesh per chunk column ("proxy") instead of their real sections.
 *
 * <p>The shadow pass is geometry-bound and draws every selected section in the light's box with its full mesh. Beyond a
 * horizontal distance D from the camera (config {@code shadowProxyDistance=}, default 96 blocks; 0 = off;
 * {@code -Dcadence.shadowProxyDistance=} overrides), each chunk column is drawn as a height field instead: for every block
 * column, its highest shadow caster (a block with a full collision box, or leaves; plants, snow layers, carpets, fluids
 * don't count) as a top face, plus walls down to each lower neighbour (low sun). Faces of equal height and pack material
 * are merged. Vertices go through the pack's own terrain path (section/block context and the BufferBuilder mixin), so the
 * pack's shadow vertex shader (distortion, waving) handles them like terrain; they are textured with stone's sprite (an
 * opaque texel: at that distance a shadow texel is a block or more, so leaf holes don't resolve anyway).
 *
 * <p>Columns are grouped in 8×8 regions, one vertex buffer each, drawn through vanilla's own
 * ChunkSectionsToRender.DrawIndirect with one indirect command per far column selected this frame (instance data = the
 * column's origin, as vanilla's section info). A column not built yet keeps its real sections. Built on the render
 * thread, 16 columns per frame, nearest first; rebuilt when one of its sections (or a neighbour's, for border walls) is
 * marked dirty.
 *
 * <p>Trade-off (flagged): beyond D, shadows come from a height field — no overhangs, caves or arches, trees as solid
 * canopies.
 */
public final class ShadowProxies {
	private static final int REGION = 8, BUILD_BUDGET = 16;
	private static final boolean LOG = Boolean.getBoolean("cadence.probe") || System.getProperty("cadence.selftest") != null;
	private static volatile int distance = parseDistance();
	private static ShadowProxies instance;

	private final ClientLevel level;
	private final Object geometryKey;
	private final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	private GpuBuffer infoBuffer, indirectBuffer;
	private int infoCapacity;
	private long built, drawn, frames, logged;

	private static final class Column {
		ByteBuffer vertices; // native, owned; null: empty column
		int quads;
	}

	private static final class Region {
		GpuBuffer buffer;
		boolean stale = true;
		final int[] firstVertex = new int[REGION * REGION], quads = new int[REGION * REGION];
	}

	private ShadowProxies(ClientLevel level, Object geometryKey) {
		this.level = level;
		this.geometryKey = geometryKey;
	}

	private static int parseDistance() {
		String v = System.getProperty("cadence.shadowProxyDistance",
			dev.cadence.shaders.cadence.ui.ShaderToggle.configValue("shadowProxyDistance", "96"));
		try {
			int d = Integer.parseInt(v.strip());
			return d <= 0 ? 0 : Math.max(32, d);
		} catch (NumberFormatException e) {
			return 96;
		}
	}

	public static int distance() {
		return distance;
	}

	/** Self-test A/B (not saved). */
	public static void setDistance(int blocks) {
		distance = blocks <= 0 ? 0 : Math.max(32, blocks);
	}

	/** Cadence (WorldClosed): the world was left: drop the proxies and the level they read (it held the old world alive). */
	public static void release() {
		if (instance != null) {
			instance.close();
			instance = null;
		}
	}

	/** The current level's proxies (a new world, pack layout or level drops the old ones). Render thread. */
	public static ShadowProxies get() {
		Minecraft mc = Minecraft.getInstance();
		if (distance == 0 || mc.level == null || !TerrainShaderGeometry.isEnabled()) {
			if (instance != null) {
				instance.close();
				instance = null;
			}
			return null;
		}
		Object key = TerrainShaderGeometry.configurationKey();
		if (instance == null || instance.level != mc.level || instance.geometryKey != key) {
			if (instance != null) instance.close();
			instance = new ShadowProxies(mc.level, key);
		}
		return instance;
	}

	/** LevelExtractor.setSectionDirty: the column (and its neighbours' border walls) must be rebuilt. Render thread. */
	public static void sectionDirty(int sectionX, int sectionZ) {
		ShadowProxies p = instance;
		if (p == null) return;
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++) {
				long key = ChunkPos.pack(sectionX + dx, sectionZ + dz);
				if (p.columns.containsKey(key)) p.dirty.add(key);
			}
	}

	/** Horizontal distance check for a section origin: draw its column as a proxy (if built) instead of its mesh. */
	public boolean far(double camX, double camZ, int sectionX, int sectionZ) {
		double dx = Math.max(Math.abs(sectionX * 16 + 8 - camX) - 8, 0), dz = Math.max(Math.abs(sectionZ * 16 + 8 - camZ) - 8, 0);
		return dx * dx + dz * dz > (double) distance * distance;
	}

	/** Whether the column's proxy is ready (built and not dirty). */
	public boolean ready(long column) {
		return columns.containsKey(column) && !dirty.contains(column);
	}

	// ---- per shadow frame (render thread, before the shadow pass opens) ----

	private final LongArrayList frameColumns = new LongArrayList();
	private List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> frameGroups;
	private GpuBufferSlice frameInfos;
	private int frameMaxIndices;

	/**
	 * Builds what this frame needs (budgeted), then uploads the frame's instance data and indirect commands for the far
	 * columns given. Returns the columns that will be drawn as proxies (the caller drops their real sections).
	 */
	public LongOpenHashSet prepare(LongOpenHashSet wanted, double camX, double camZ) {
		frames++;
		frameColumns.clear();
		frameGroups = null;
		// build: dirty and missing wanted columns, nearest first
		LongArrayList todo = new LongArrayList();
		for (long c : wanted) if (!columns.containsKey(c) || dirty.contains(c)) todo.add(c);
		todo.sort((a, b) -> Double.compare(dist2(a, camX, camZ), dist2(b, camX, camZ)));
		// a pack check needs the picture settled: everything at once while it runs
		int budget = dev.cadence.shaders.cadence.packcheck.PackCheck.running() ? todo.size() : BUILD_BUDGET;
		for (int i = 0; i < Math.min(budget, todo.size()); i++) build(todo.getLong(i));
		// drop columns far outside anything wanted for a while is left to close() (world/RD change); regions rebuilt lazily
		LongOpenHashSet ready = new LongOpenHashSet();
		for (long c : wanted) if (ready(c)) ready.add(c);
		if (ready.isEmpty()) return ready;
		// group by region, (re)upload stale regions
		Long2ObjectOpenHashMap<LongArrayList> byRegion = new Long2ObjectOpenHashMap<>();
		for (long c : ready) {
			int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
			byRegion.computeIfAbsent(ChunkPos.pack(Math.floorDiv(cx, REGION), Math.floorDiv(cz, REGION)), k -> new LongArrayList()).add(c);
		}
		int drawCount = 0;
		for (long c : ready) if (columns.get(c).quads > 0) drawCount++;
		if (drawCount == 0) return ready;
		ensureFrameBuffers(drawCount);
		ByteBuffer infos = MemoryUtil.memAlloc(drawCount * 16).order(ByteOrder.nativeOrder());
		ByteBuffer commands = MemoryUtil.memAlloc(drawCount * 20).order(ByteOrder.nativeOrder());
		List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> groups = new ArrayList<>();
		var sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		int maxQuads = 0, n = 0;
		for (var e : byRegion.long2ObjectEntrySet()) {
			Region r = region(e.getLongKey());
			if (r.buffer == null) continue;
			int first = n;
			for (long c : e.getValue()) {
				int slot = slot(c);
				int quads = r.quads[slot];
				if (quads == 0) continue;
				maxQuads = Math.max(maxQuads, quads);
				infos.putInt(ChunkPos.getX(c) * 16).putInt(0).putInt(ChunkPos.getZ(c) * 16).putFloat(1.0f);
				commands.putInt(quads * 6).putInt(1).putInt(0).putInt(r.firstVertex[slot]).putInt(n);
				frameColumns.add(c);
				n++;
			}
			if (n > first) groups.add(new ChunkSectionsToRender.GpuMultiDrawIndexedIndirect(r.buffer.slice(), null, null,
				indirectBuffer.slice(first * 20L, (n - first) * 20L), n - first));
		}
		infos.flip();
		commands.flip();
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.writeToBuffer(infoBuffer.slice(0, infos.remaining()), infos);
		encoder.writeToBuffer(indirectBuffer.slice(0, commands.remaining()), commands);
		MemoryUtil.memFree(infos);
		MemoryUtil.memFree(commands);
		sequential.requestIndexCount(maxQuads * 6);
		sequential.resizeToRequestedIndexCount();
		// the groups get the shared quad index buffer
		List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> indexed = new ArrayList<>(groups.size());
		GpuBuffer indexBuffer = sequential.getBuffer(maxQuads * 6);
		for (var g : groups)
			indexed.add(new ChunkSectionsToRender.GpuMultiDrawIndexedIndirect(g.vertexBuffer(), indexBuffer.slice(), sequential.type(),
				g.indirectCommandBuffer(), g.drawCount()));
		frameGroups = indexed;
		frameInfos = infoBuffer.slice(0, n * 16L);
		frameMaxIndices = maxQuads * 6;
		drawn += n;
		if (LOG && System.nanoTime() - logged > 30_000_000_000L) {
			logged = System.nanoTime();
			LoggerFactory.getLogger("cadence").info("Shadow proxies (D {}): {} columns built, {} drawn this frame ({} total over {} frames), {}",
				distance, built, n, drawn, frames, memory());
		}
		LongOpenHashSet drawnNow = new LongOpenHashSet(frameColumns);
		// a ready but empty column (no casters) needs no real sections either
		for (long c : ready) if (columns.get(c).quads == 0) drawnNow.add(c);
		return drawnNow;
	}

	/** Inside the shadow pass, after the real opaque sections: the proxies, with the same transform as the terrain. */
	public void draw(RenderPass pass, GpuBufferSlice terrainTransform, GpuSampler sampler, GpuTextureView atlas) {
		if (frameGroups == null || frameGroups.isEmpty()) return;
		EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> perLayer = new EnumMap<>(ChunkSectionLayer.class);
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) perLayer.put(layer, List.of());
		perLayer.put(ChunkSectionLayer.SOLID, frameGroups);
		new ChunkSectionsToRender.DrawIndirect(terrainTransform, perLayer, frameMaxIndices, frameInfos)
			.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false);
	}

	private void ensureFrameBuffers(int draws) {
		if (infoBuffer != null && infoCapacity >= draws) return;
		if (infoBuffer != null) infoBuffer.close();
		if (indirectBuffer != null) indirectBuffer.close();
		infoCapacity = Math.max(256, Integer.highestOneBit(draws) * 2);
		var device = RenderSystem.getDevice();
		infoBuffer = device.createBuffer(() -> "Cadence shadow proxy sections", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, infoCapacity * 16L);
		indirectBuffer = device.createBuffer(() -> "Cadence shadow proxy draws", GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST,
			infoCapacity * 20L);
	}

	private Region region(long regionKey) {
		Region r = regions.computeIfAbsent(regionKey, k -> new Region());
		if (r.stale) {
			r.stale = false;
			int rx = ChunkPos.getX(regionKey), rz = ChunkPos.getZ(regionKey);
			int total = 0, vertexSize = TerrainShaderGeometry.layout().stride();
			for (int i = 0; i < REGION * REGION; i++) {
				Column c = columns.get(ChunkPos.pack(rx * REGION + i % REGION, rz * REGION + i / REGION));
				r.firstVertex[i] = total / vertexSize;
				r.quads[i] = c == null ? 0 : c.quads;
				if (c != null && c.vertices != null) total += c.vertices.remaining();
			}
			if (r.buffer != null) {
				r.buffer.close();
				r.buffer = null;
			}
			if (total > 0) {
				ByteBuffer all = MemoryUtil.memAlloc(total);
				for (int i = 0; i < REGION * REGION; i++) {
					Column c = columns.get(ChunkPos.pack(rx * REGION + i % REGION, rz * REGION + i / REGION));
					if (c != null && c.vertices != null) all.put(c.vertices.duplicate());
				}
				all.flip();
				r.buffer = RenderSystem.getDevice().createBuffer(() -> "Cadence shadow proxy region", GpuBuffer.USAGE_VERTEX, all);
				MemoryUtil.memFree(all);
			}
		}
		return r;
	}

	private static int slot(long column) {
		return Math.floorMod(ChunkPos.getX(column), REGION) + Math.floorMod(ChunkPos.getZ(column), REGION) * REGION;
	}

	private static double dist2(long c, double camX, double camZ) {
		double dx = ChunkPos.getX(c) * 16 + 8 - camX, dz = ChunkPos.getZ(c) * 16 + 8 - camZ;
		return dx * dx + dz * dz;
	}

	// ---- building one column ----

	private void build(long key) {
		dirty.remove(key);
		int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
		Column old = columns.get(key);
		Column col = new Column();
		LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
		if (chunk == null) {
			if (old != null && old.vertices != null) MemoryUtil.memFree(old.vertices);
			columns.remove(key);
			return;
		}
		int[] top = new int[18 * 18];
		BlockState[] state = new BlockState[16 * 16];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = -1; z <= 16; z++)
			for (int x = -1; x <= 16; x++) {
				boolean inside = x >= 0 && x < 16 && z >= 0 && z < 16;
				if (!inside && (x == -1 || x == 16) && (z == -1 || z == 16)) continue; // corners unused
				LevelChunk c = inside ? chunk : level.getChunkSource().getChunk(cx + Math.floorDiv(x, 16), cz + Math.floorDiv(z, 16), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
				int idx = (x + 1) + (z + 1) * 18;
				if (c == null) {
					top[idx] = Integer.MIN_VALUE; // unknown neighbour: no wall toward it
					continue;
				}
				int lx = Math.floorMod(x, 16), lz = Math.floorMod(z, 16);
				int minY = level.getMinY(), y;
				try {
					y = c.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
				} catch (RuntimeException e) {
					y = level.getMaxY(); // no surface heightmap on this chunk: scan from the top
				}
				BlockState found = null;
				for (int steps = 0; y >= minY && steps < 96; y--, steps++) {
					pos.set(cx * 16 + x, y, cz * 16 + z);
					BlockState s = c.getBlockState(pos);
					if (caster(s, pos)) {
						found = s;
						break;
					}
				}
				top[idx] = found == null ? minY : y + 1;
				if (inside) state[lx + lz * 16] = found;
			}
		ByteBufferBuilder memory = new ByteBufferBuilder(16 * 1024);
		var layout = TerrainShaderGeometry.layout();
		BufferBuilder builder = new BufferBuilder(memory, PrimitiveTopology.QUADS, layout.format());
		var sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(Blocks.STONE.defaultBlockState());
		var stone = sprite.sprite();
		float u = (stone.getU0() + stone.getU1()) * 0.5f, v = (stone.getV0() + stone.getV1()) * 0.5f;
		var previous = TerrainShaderGeometry.beginSection();
		int quads = 0;
		try {
			quads += tops(builder, top, state, cx, cz, u, v, pos);
			quads += walls(builder, top, state, cx, cz, u, v, pos);
		} finally {
			TerrainShaderGeometry.endSection(previous);
		}
		MeshData mesh = quads == 0 ? null : builder.build();
		if (mesh != null) {
			ByteBuffer src = mesh.vertexBuffer();
			col.vertices = MemoryUtil.memAlloc(src.remaining());
			col.vertices.put(src).flip();
			col.quads = quads;
			mesh.close();
		}
		memory.close();
		if (old != null && old.vertices != null) MemoryUtil.memFree(old.vertices);
		columns.put(key, col);
		Region r = regions.get(ChunkPos.pack(Math.floorDiv(cx, REGION), Math.floorDiv(cz, REGION)));
		if (r != null) r.stale = true;
		built++;
	}

	private boolean caster(BlockState s, BlockPos pos) {
		if (s.isAir() || !s.getFluidState().isEmpty() && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return false;
		if (s.is(BlockTags.LEAVES)) return true;
		return s.isCollisionShapeFullBlock(level, pos);
	}

	/** Top faces, merged into rectangles of equal height and pack material. */
	private int tops(BufferBuilder b, int[] top, BlockState[] state, int cx, int cz, float u, float v, BlockPos.MutableBlockPos pos) {
		boolean[] used = new boolean[256];
		int quads = 0;
		for (int z = 0; z < 16; z++)
			for (int x = 0; x < 16; x++) {
				int i = x + z * 16;
				if (used[i] || state[i] == null) continue;
				int h = top[(x + 1) + (z + 1) * 18];
				BlockState s = state[i];
				int w = 1;
				while (x + w < 16 && same(top, state, used, x + w, z, h, s)) w++;
				int d = 1;
				outer:
				while (z + d < 16) {
					for (int k = 0; k < w; k++) if (!same(top, state, used, x + k, z + d, h, s)) break outer;
					d++;
				}
				for (int dz = 0; dz < d; dz++)
					for (int dx = 0; dx < w; dx++) used[(x + dx) + (z + dz) * 16] = true;
				pos.set(cx * 16 + x, h - 1, cz * 16 + z);
				TerrainShaderGeometry.beginBlock(s, pos, false);
				// vanilla's UP face order
				vertex(b, x, h, z, u, v, 0, 1, 0);
				vertex(b, x, h, z + d, u, v, 0, 1, 0);
				vertex(b, x + w, h, z + d, u, v, 0, 1, 0);
				vertex(b, x + w, h, z, u, v, 0, 1, 0);
				TerrainShaderGeometry.endBlock();
				quads++;
			}
		return quads;
	}

	private static boolean same(int[] top, BlockState[] state, boolean[] used, int x, int z, int h, BlockState s) {
		int i = x + z * 16;
		return !used[i] && state[i] != null && top[(x + 1) + (z + 1) * 18] == h && sameMaterial(state[i], s);
	}

	private static boolean sameMaterial(BlockState a, BlockState b) {
		return a == b || a.getBlock() == b.getBlock() || a.is(BlockTags.LEAVES) == b.is(BlockTags.LEAVES) && !a.is(BlockTags.LEAVES)
			&& !b.is(BlockTags.LEAVES) && a.getBlock().defaultMapColor() == b.getBlock().defaultMapColor();
	}

	/** Walls down to each lower neighbour, merged along runs of equal heights. */
	private int walls(BufferBuilder b, int[] top, BlockState[] state, int cx, int cz, float u, float v, BlockPos.MutableBlockPos pos) {
		int quads = 0;
		// 0: -z (north), 1: +z (south), 2: -x (west), 3: +x (east)
		for (int dir = 0; dir < 4; dir++) {
			for (int line = 0; line < 16; line++) {
				int k = 0;
				while (k < 16) {
					int x = dir < 2 ? k : (dir == 2 ? 0 : 15), z = dir < 2 ? (dir == 0 ? 0 : 15) : k;
					// along the line: for N/S walls the line is z rows, for W/E the line is x columns
					if (dir < 2) z = line;
					else x = line;
					int nx = x + (dir == 2 ? -1 : dir == 3 ? 1 : 0), nz = z + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
					int h = top[(x + 1) + (z + 1) * 18], hn = top[(nx + 1) + (nz + 1) * 18];
					BlockState s = state[x + z * 16];
					if (s == null || hn == Integer.MIN_VALUE || hn >= h) {
						k++;
						continue;
					}
					int run = 1;
					while (k + run < 16) {
						int x2 = dir < 2 ? k + run : x, z2 = dir < 2 ? z : k + run;
						int nx2 = x2 + (dir == 2 ? -1 : dir == 3 ? 1 : 0), nz2 = z2 + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
						BlockState s2 = state[x2 + z2 * 16];
						if (s2 == null || top[(x2 + 1) + (z2 + 1) * 18] != h || top[(nx2 + 1) + (nz2 + 1) * 18] != hn || !sameMaterial(s, s2)) break;
						run++;
					}
					pos.set(cx * 16 + x, h - 1, cz * 16 + z);
					TerrainShaderGeometry.beginBlock(s, pos, false);
					float lo = hn, hi = h;
					switch (dir) {
						case 0 -> { // NORTH (-z): vanilla order (maxX,maxY,minZ),(maxX,minY,minZ),(minX,minY,minZ),(minX,maxY,minZ)
							float x0 = k, x1 = k + run, zz = z;
							vertex(b, x1, hi, zz, u, v, 0, 0, -1);
							vertex(b, x1, lo, zz, u, v, 0, 0, -1);
							vertex(b, x0, lo, zz, u, v, 0, 0, -1);
							vertex(b, x0, hi, zz, u, v, 0, 0, -1);
						}
						case 1 -> { // SOUTH (+z): (minX,maxY,maxZ),(minX,minY,maxZ),(maxX,minY,maxZ),(maxX,maxY,maxZ)
							float x0 = k, x1 = k + run, zz = z + 1;
							vertex(b, x0, hi, zz, u, v, 0, 0, 1);
							vertex(b, x0, lo, zz, u, v, 0, 0, 1);
							vertex(b, x1, lo, zz, u, v, 0, 0, 1);
							vertex(b, x1, hi, zz, u, v, 0, 0, 1);
						}
						case 2 -> { // WEST (-x): (minX,maxY,minZ),(minX,minY,minZ),(minX,minY,maxZ),(minX,maxY,maxZ)
							float z0 = k, z1 = k + run, xx = x;
							vertex(b, xx, hi, z0, u, v, -1, 0, 0);
							vertex(b, xx, lo, z0, u, v, -1, 0, 0);
							vertex(b, xx, lo, z1, u, v, -1, 0, 0);
							vertex(b, xx, hi, z1, u, v, -1, 0, 0);
						}
						default -> { // EAST (+x): (maxX,maxY,maxZ),(maxX,minY,maxZ),(maxX,minY,minZ),(maxX,maxY,minZ)
							float z0 = k, z1 = k + run, xx = x + 1;
							vertex(b, xx, hi, z1, u, v, 1, 0, 0);
							vertex(b, xx, lo, z1, u, v, 1, 0, 0);
							vertex(b, xx, lo, z0, u, v, 1, 0, 0);
							vertex(b, xx, hi, z0, u, v, 1, 0, 0);
						}
					}
					TerrainShaderGeometry.endBlock();
					quads++;
					k += run;
				}
			}
		}
		return quads;
	}

	private static void vertex(BufferBuilder b, float x, float y, float z, float u, float v, float nx, float ny, float nz) {
		b.addVertex(x, y, z, 0xFFFFFFFF, u, v, 0, 0xF000F0, nx, ny, nz);
	}

	public String memory() {
		long bytes = 0;
		for (Column c : columns.values()) if (c.vertices != null) bytes += c.vertices.capacity();
		return String.format(Locale.ROOT, "%d columns, %.1f MB of proxy vertices (CPU copy; same again on the GPU)", columns.size(), bytes / 1048576.0);
	}

	private void close() {
		for (Column c : columns.values()) if (c.vertices != null) MemoryUtil.memFree(c.vertices);
		columns.clear();
		for (Region r : regions.values()) if (r.buffer != null) r.buffer.close();
		regions.clear();
		if (infoBuffer != null) infoBuffer.close();
		if (indirectBuffer != null) indirectBuffer.close();
		infoBuffer = indirectBuffer = null;
	}
}
