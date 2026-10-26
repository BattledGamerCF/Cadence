package dev.cadence.shaders.cadence.server;

import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.jspecify.annotations.Nullable;

/**
 * Keep Server Chunks on disk: a server's chunks as the client last had them, so a world, or a dimension left and come back
 * to, shows its land at once instead of waiting for the server to send it again (as Bobby does). Each chunk is stored as the
 * very packet a server sends for it (ClientboundLevelChunkWithLightPacket: blocks, biomes, heightmaps, block entities, light),
 * made from the client's chunk when the client lets it go (out of the render distance, the world or dimension left), in
 * vanilla's region files under cadence-chunks/servers/ADDRESS-SEED/DIMENSION/. Coming back, the stored chunks within the render
 * distance that the client doesn't have yet are handed to vanilla's own chunk-packet handler, a few a frame; the server's
 * own copy replaces each as it arrives, and those it never sends (past its view distance) are kept as ServerChunks keeps
 * the chunks it forgets. An entry is used only for the same game protocol, world height and block and biome registries (a
 * changed server or game version leaves it unread), and a dimension's store is cleared when the world behind the address
 * isn't the one it was saved from (world.txt: the game time and spawn at the last save; an earlier time or another spawn means
 * a reset map or another server behind a proxy). Files and decoding on one background thread, the decoded chunks at most
 * READY_MAX ahead of the game; what waits to be written is held to WRITE_QUEUE_MAX bytes (a save past it is skipped: it is a
 * cache), leaving a world saves farthest first for at most 40 ms, and chunks that came from disk unchanged aren't written
 * again. The store is trimmed to its budget (-Dcadence.serverChunks.diskMb, 512) at the first world of a session and again
 * every quarter of the budget written, least recently written region first. -Dcadence.serverChunks.disk=false: kept in
 * memory only.
 */
public final class ServerChunkStore {
	static final boolean ENABLED = !"false".equals(System.getProperty("cadence.serverChunks.disk"));
	private static final long BUDGET = Long.getLong("cadence.serverChunks.diskMb", 512L) << 20;
	private static final int MAGIC = 0x43444348, VERSION = 1, OPEN_REGIONS = 8;
	/** The render thread's budget for stored chunks handed to the game, per frame. */
	private static final long APPLY_NANOS = 2_000_000L;
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "cadence-server-chunks");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private static final Map<Long, RegionFile> REGIONS = new LinkedHashMap<>(16, 0.75f, true);  // (IO thread only)
	/** Decoded chunks for the render thread, each with the generation (the open) it was read for. */
	private record Ready(int generation, ClientboundLevelChunkWithLightPacket packet) {
	}

	private static final ConcurrentLinkedQueue<Ready> READY = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger READY_COUNT = new AtomicInteger();
	private static final int READY_MAX = 64;
	/** Bytes handed to the IO thread and not written yet (BH6-01), and written since the last trim (BH6-04). */
	private static final java.util.concurrent.atomic.AtomicLong QUEUED = new java.util.concurrent.atomic.AtomicLong(),
		WRITTEN = new java.util.concurrent.atomic.AtomicLong();
	private static final long WRITE_QUEUE_MAX = 48L << 20, CLOSE_NANOS = 40_000_000L;
	/** Positions applied from disk and not sent by the server since: unchanged, not written again (render thread). */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet FROM_DISK = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private static volatile boolean writeFailed;
	/** Which level the store is open for; loads queued for an earlier one are dropped (render thread). */
	private static @Nullable ClientLevel openFor;
	private static @Nullable Path dir;
	private static long fingerprint;
	private static final AtomicInteger GENERATION = new AtomicInteger();
	private static int loaded, saved;
	private static boolean pruned;

	private ServerChunkStore() {
	}

	/** The level's store opened (render thread; a remote level, Keep Server Chunks on) and its chunks around (cx, cz) read. */
	static void open(Minecraft mc, ClientLevel level, int cx, int cz, int radius) {
		if (!ENABLED) return;
		if (openFor == level) {
			// (a teleport within the level, a lobby moving the player after the join: read around the new place, BH6-11)
			if (dir != null && Math.max(Math.abs(cx - loadX), Math.abs(cz - loadZ)) > Math.max(2, radius / 2)) load(mc, level, cx, cz, radius, false);
			return;
		}
		openFor = level;
		FROM_DISK.clear();
		load(mc, level, cx, cz, radius, true);
	}

	/** Where the last load read around (render thread). */
	private static int loadX, loadZ;

	/** The stored chunks around (cx, cz) read on the IO thread (first: the level's first load, which also checks world.txt). */
	private static void load(Minecraft mc, ClientLevel level, int cx, int cz, int radius, boolean first) {
		clearReady();
		loadX = cx;
		loadZ = cz;
		int generation = GENERATION.incrementAndGet();
		var connection = mc.getConnection();
		if (connection == null) return;
		if (first) {
			dir = folder(mc, level);
			fingerprint = fingerprint(level);
			loaded = saved = 0;
		}
		if (dir == null) return;
		Path d = dir;
		long fp = fingerprint;
		int sections = level.getSectionsCount();
		var registries = connection.registryAccess();
		long time = level.getLevelData().getGameTime();
		String spawn = spawnOf(level);
		if (!pruned) {
			pruned = true;
			IO.execute(() -> prune(root(d)));
		}
		IO.execute(() -> {
			if (first && !sameWorld(d, time, spawn)) return;
			int n = 0;
			// (nearest first: rings around the player's chunk)
			for (int r = 0; r <= radius && GENERATION.get() == generation; r++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
						// (back pressure: at most READY_MAX decoded ahead of the game, BH6-06)
						while (READY_COUNT.get() >= READY_MAX && GENERATION.get() == generation) sleepQuietly(2);
						if (GENERATION.get() != generation) break;
						var packet = read(d, new ChunkPos(cx + dx, cz + dz), fp, sections, registries);
						if (packet != null) {
							READY.add(new Ready(generation, packet));
							READY_COUNT.incrementAndGet();
							n++;
						}
					}
				}
			}
			if (n > 0) System.out.println("[cadence] Keep Server Chunks: " + n + " chunks from disk for " + level.dimension().identifier());
		});
	}

	/** The level going (render thread): every chunk the client has saved, then its region files closed. */
	static void close(Minecraft mc, ClientLevel level, int cx, int cz, int radius) {
		if (openFor != level) return;
		// farthest first (the chunks past the server's view distance, which it won't send again: the ones worth storing; the
		// near ones come back from the server within seconds), for at most CLOSE_NANOS (inside the disconnect or the
		// dimension change, BH6-01)
		long end = System.nanoTime() + CLOSE_NANOS;
		outer:
		for (int r = radius; r >= 0; r--) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					if (System.nanoTime() > end) break outer;
					LevelChunk chunk = level.getChunkSource().getChunk(cx + dx, cz + dz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
					if (chunk != null) save(mc, level, chunk);
				}
			}
		}
		Path d = dir;
		long time = level.getLevelData().getGameTime();
		String spawn = spawnOf(level);
		reset();
		if (saved > 0 || loaded > 0) System.out.println("[cadence] Keep Server Chunks: " + saved + " chunks saved, " + loaded + " used from disk");
		if (d != null) IO.execute(() -> writeWorld(d, time, spawn));
	}

	/** The store let go of without saving (Keep Server Chunks turned off, the world gone without a hook seeing it, BH6-05/07). */
	public static void reset() {
		openFor = null;
		dir = null;
		clearReady();
		FROM_DISK.clear();
		GENERATION.incrementAndGet();
		IO.execute(ServerChunkStore::closeRegions);
	}

	private static void clearReady() {
		READY.clear();
		READY_COUNT.set(0);
	}

	/** A chunk the client is letting go (render thread): stored as the packet that would bring it back. */
	static void save(Minecraft mc, ClientLevel level, LevelChunk chunk) {
		if (openFor != level || dir == null) return;
		var connection = mc.getConnection();
		if (connection == null) return;
		// (from disk and never sent since: what is stored already; the queue over its bound: skipped, it is a cache)
		if (FROM_DISK.remove(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z())) || QUEUED.get() > WRITE_QUEUE_MAX) return;
		byte[] bytes;
		try {
			var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getChunkSource().getLightEngine(), null, null);
			RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(16384), connection.registryAccess());
			try {
				ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, packet);
				bytes = new byte[buf.readableBytes()];
				buf.readBytes(bytes);
			} finally {
				buf.release();
			}
		} catch (RuntimeException e) {
			return;  // (a block entity that can't write its update tag on the client, and the like: not stored)
		}
		saved++;
		Path d = dir;
		long fp = fingerprint;
		int sections = level.getSectionsCount();
		ChunkPos pos = chunk.getPos();
		QUEUED.addAndGet(bytes.length);
		IO.execute(() -> {
			write(d, pos, fp, sections, bytes);
			QUEUED.addAndGet(-bytes.length);
			// (trimmed again every quarter of the budget written, BH6-04: a long session wrote without limit)
			if (WRITTEN.addAndGet(bytes.length / 3) > BUDGET / 4) {
				WRITTEN.set(0);
				closeRegions();
				prune(root(d));
			}
		});
	}

	/** ServerChunks.sent: the server's own copy arrived; the chunk is the server's again. */
	static void sentByServer(long packed) {
		if (!FROM_DISK.isEmpty()) FROM_DISK.remove(packed);
	}

	/** Every frame (render thread): stored chunks handed to the game within the budget; returns the positions given. */
	static void apply(Minecraft mc, ClientLevel level, java.util.function.LongConsumer kept, int radius) {
		if (openFor != level || READY.isEmpty() || mc.player == null) return;
		var connection = mc.getConnection();
		if (connection == null) return;
		long end = System.nanoTime() + APPLY_NANOS;
		int pcx = mc.player.chunkPosition().x(), pcz = mc.player.chunkPosition().z();
		Ready ready;
		int generation = GENERATION.get();
		while (System.nanoTime() < end && (ready = READY.poll()) != null) {
			READY_COUNT.decrementAndGet();
			if (ready.generation() != generation) continue;  // (read for an earlier open, BH6-10)
			var packet = ready.packet();
			int x = packet.x(), z = packet.z();
			if (Math.max(Math.abs(x - pcx), Math.abs(z - pcz)) > radius || level.getChunkSource().hasChunk(x, z)) continue;
			try {
				connection.handleLevelChunkWithLight(packet);
			} catch (RuntimeException e) {
				System.out.println("[cadence] Keep Server Chunks: a stored chunk at " + x + ", " + z + " couldn't be read; the store for this dimension is off: " + e);
				clearReady();
				dir = null;
				return;
			}
			kept.accept(ChunkPos.pack(x, z));
			FROM_DISK.add(ChunkPos.pack(x, z));
			loaded++;
		}
	}

	// ---- disk (the IO thread) ----

	private static void write(Path d, ChunkPos pos, long fp, int sections, byte[] bytes) {
		try {
			RegionFile region = region(d, pos, true);
			if (region == null) return;
			try (DataOutputStream out = region.getChunkDataOutputStream(pos)) {
				out.writeInt(MAGIC);
				out.writeInt(VERSION);
				out.writeInt(net.minecraft.SharedConstants.getProtocolVersion());
				out.writeInt(sections);
				out.writeLong(fp);
				out.writeInt(bytes.length);
				out.write(bytes);
			}
		} catch (IOException | RuntimeException e) {
			if (!writeFailed) System.out.println("[cadence] Keep Server Chunks: couldn't store a chunk (said once): " + e);
			writeFailed = true;
		}
	}

	private static @Nullable ClientboundLevelChunkWithLightPacket read(Path d, ChunkPos pos, long fp, int levelSections,
		net.minecraft.core.RegistryAccess registries) {
		try {
			RegionFile region = region(d, pos, false);
			if (region == null || !region.hasChunk(pos)) return null;
			try (DataInputStream in = region.getChunkDataInputStream(pos)) {
				if (in == null || in.readInt() != MAGIC || in.readInt() != VERSION || in.readInt() != net.minecraft.SharedConstants.getProtocolVersion())
					return null;
				int sections = in.readInt();
				if (in.readLong() != fp) return null;
				int length = in.readInt();
				if (length <= 0 || length > 16 << 20) return null;
				byte[] bytes = in.readNBytes(length);
				if (bytes.length != length) return null;
				if (sections != levelSections) return null;
				RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registries);
				var packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);
				return packet.x() == pos.x() && packet.z() == pos.z() ? packet : null;
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static @Nullable RegionFile region(Path d, ChunkPos pos, boolean create) throws IOException {
		int rx = pos.getRegionX(), rz = pos.getRegionZ();
		long key = (long) rx << 32 | (rz & 0xFFFFFFFFL);
		RegionFile region = REGIONS.get(key);
		if (region != null && region.getPath().getParent().equals(d)) return region;
		Path file = d.resolve("r." + rx + "." + rz + ".mca");
		if (!create && !Files.isRegularFile(file)) return null;
		Files.createDirectories(d);
		if (region != null) {
			region.close();
			REGIONS.remove(key);
		}
		region = new RegionFile(new RegionStorageInfo("cadence-server-chunks", net.minecraft.world.level.Level.OVERWORLD, "chunk"), file, d, false);
		REGIONS.put(key, region);
		while (REGIONS.size() > OPEN_REGIONS) {
			var eldest = REGIONS.entrySet().iterator().next();
			REGIONS.remove(eldest.getKey());
			eldest.getValue().close();
		}
		return region;
	}

	private static void closeRegions() {
		for (RegionFile region : REGIONS.values()) {
			try {
				region.close();
			} catch (IOException ignored) {
			}
		}
		REGIONS.clear();
	}

	/** The whole store trimmed to 3/4 of its budget when over it, least recently written region files first. */
	private static void prune(Path root) {
		List<Path> files = new ArrayList<>();
		long total = 0;
		try (var walk = Files.walk(root)) {
			for (Path p : (Iterable<Path>) walk::iterator) {
				if (!Files.isRegularFile(p) || !p.getFileName().toString().endsWith(".mca")) continue;
				files.add(p);
				total += Files.size(p);
			}
			if (total <= BUDGET) return;
			files.sort(Comparator.comparingLong(p -> {
				try {
					return Files.getLastModifiedTime(p).toMillis();
				} catch (IOException e) {
					return 0L;
				}
			}));
			long before = total;
			for (Path p : files) {
				if (total <= BUDGET * 3 / 4) break;
				long size = Files.size(p);
				Files.deleteIfExists(p);
				total -= size;
				// (and the region's external chunks, c.X.Z.mcc next to it: RegionFile's oversized chunks)
				String name = p.getFileName().toString();
				String[] rz = name.substring(2, name.length() - 4).split("\\.");
				if (rz.length == 2 && p.getParent() != null) {
					try (var mcc = Files.list(p.getParent())) {
						for (Path c : (Iterable<Path>) mcc::iterator) {
							String cn = c.getFileName().toString();
							if (!cn.startsWith("c.") || !cn.endsWith(".mcc")) continue;
							String[] xz = cn.substring(2, cn.length() - 4).split("\\.");
							try {
								if (xz.length == 2 && Integer.parseInt(xz[0]) >> 5 == Integer.parseInt(rz[0]) && Integer.parseInt(xz[1]) >> 5 == Integer.parseInt(rz[1]))
									Files.deleteIfExists(c);
							} catch (NumberFormatException ignored) {
							}
						}
					}
				}
			}
			System.out.println("[cadence] Keep Server Chunks: store trimmed from " + (before >> 20) + " to " + (total >> 20) + " MB");
		} catch (IOException | RuntimeException e) {
			// (a store that can't be trimmed is only bigger)
		}
	}

	private static Path root(Path dimensionDir) {
		Path server = dimensionDir.getParent();
		return server != null && server.getParent() != null ? server.getParent() : dimensionDir;
	}

	private static void sleepQuietly(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** The overworld spawn the server sent ("" before it has), to tell worlds behind one address apart. */
	private static String spawnOf(ClientLevel level) {
		var r = level.getRespawnData();
		return r == null || r.equals(net.minecraft.world.level.storage.LevelData.RespawnData.DEFAULT) ? "" : r.pos().getX() + " " + r.pos().getZ();
	}

	/**
	 * (IO thread) Whether the stored dimension is this world's (BH6-02): world.txt holds the game time and spawn at the last
	 * save; a game time earlier than that by more than a minute (a reset map, the same seed) or another spawn (a different
	 * world behind the same address and seed: a proxy's other server, a minigame) clears the dimension's store.
	 */
	private static boolean sameWorld(Path d, long time, String spawn) {
		Path f = d.resolve("world.txt");
		if (!Files.isRegularFile(f)) return true;
		try {
			String[] w = Files.readString(f).strip().split("\\|", -1);
			long was = Long.parseLong(w[0]);
			String wasSpawn = w.length > 1 ? w[1] : "";
			boolean same = time >= was - 1200 && (spawn.isEmpty() || wasSpawn.isEmpty() || spawn.equals(wasSpawn));
			if (!same) {
				closeRegions();
				try (var files = Files.list(d)) {
					for (Path p : (Iterable<Path>) files::iterator) Files.deleteIfExists(p);
				}
				System.out.println("[cadence] Keep Server Chunks: another world behind this address; its stored chunks cleared");
			}
			return same;
		} catch (IOException | RuntimeException e) {
			return true;
		}
	}

	private static void writeWorld(Path d, long time, String spawn) {
		try {
			if (Files.isDirectory(d)) Files.writeString(d.resolve("world.txt"), time + "|" + spawn + "\n");
		} catch (IOException ignored) {
		}
	}

	// ---- where, and which entries fit ----

	/** cadence-chunks/servers/ADDRESS-SEED/DIMENSION under the game folder, or null when no safe name can be made. */
	private static @Nullable Path folder(Minecraft mc, ClientLevel level) {
		var info = mc.getCurrentServer();
		String address = info != null ? info.ip : "server";
		if (info != null && info.isLan()) {
			int colon = address.lastIndexOf(':');
			if (colon > 0) address = address.substring(0, colon);  // (a LAN host's port is new every time)
		}
		long seed = level.getBiomeManager() instanceof dev.cadence.shaders.cadence.mixin.server.ServerBiomeSeedAccessor a ? a.cadence$seed() : 0;
		var id = level.dimension().identifier();
		Path root = mc.gameDirectory.toPath().resolve("cadence-chunks").resolve("servers").normalize();
		Path d = root.resolve(safe(address) + "-" + Long.toHexString(seed)).resolve(safe(id.getNamespace() + "_" + id.getPath()) + "-"
			+ Integer.toHexString(id.toString().hashCode())).normalize();
		return d.startsWith(root) ? d : null;
	}

	private static String safe(String name) {
		String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		while (s.startsWith(".")) s = "_" + s.substring(1);
		return s.isEmpty() ? "_" : s;
	}

	/** The block-state count and the server's biome registry (its ids are in the stored palettes) in one number. */
	private static long fingerprint(ClientLevel level) {
		// (and where the world starts and whether it has sky light: the section count alone missed a moved floor, BH6-09)
		long h = Block.BLOCK_STATE_REGISTRY.size() * 31L + level.getMinSectionY();
		h = h * 31 + (level.dimensionType().hasSkyLight() ? 1 : 0);
		try {
			var biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
			for (var biome : biomes) h = h * 1_000_003L + String.valueOf(biomes.getKey(biome)).hashCode();
		} catch (RuntimeException e) {
			h = h * 31 + 7;
		}
		return h;
	}
}
