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
 * changed server or game version leaves it unread). Files and decoding on one background thread; the store is trimmed to
 * its budget (-Dcadence.serverChunks.diskMb, 512) once a session, least recently written region first.
 * -Dcadence.serverChunks.disk=false: kept in memory only.
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
	private static final ConcurrentLinkedQueue<ClientboundLevelChunkWithLightPacket> READY = new ConcurrentLinkedQueue<>();
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
		if (!ENABLED || openFor == level) return;
		openFor = level;
		READY.clear();
		int generation = GENERATION.incrementAndGet();
		var connection = mc.getConnection();
		if (connection == null) return;
		dir = folder(mc, level);
		fingerprint = fingerprint(level);
		loaded = saved = 0;
		if (dir == null) return;
		Path d = dir;
		long fp = fingerprint;
		int sections = level.getSectionsCount();
		var registries = connection.registryAccess();
		if (!pruned) {
			pruned = true;
			Path root = d.getParent() != null ? d.getParent().getParent() : null;
			if (root != null) IO.execute(() -> prune(root));
		}
		IO.execute(() -> {
			int n = 0;
			// (nearest first: rings around the player's chunk)
			for (int r = 0; r <= radius && GENERATION.get() == generation; r++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
						var packet = read(d, new ChunkPos(cx + dx, cz + dz), fp, sections, registries);
						if (packet != null && GENERATION.get() == generation) {
							READY.add(packet);
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
		for (int x = cx - radius; x <= cx + radius; x++)
			for (int z = cz - radius; z <= cz + radius; z++) {
				LevelChunk chunk = level.getChunkSource().getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
				if (chunk != null) save(mc, level, chunk);
			}
		openFor = null;
		READY.clear();
		GENERATION.incrementAndGet();
		if (saved > 0 || loaded > 0) System.out.println("[cadence] Keep Server Chunks: " + saved + " chunks saved, " + loaded + " used from disk");
		IO.execute(ServerChunkStore::closeRegions);
	}

	/** A chunk the client is letting go (render thread): stored as the packet that would bring it back. */
	static void save(Minecraft mc, ClientLevel level, LevelChunk chunk) {
		if (openFor != level || dir == null) return;
		var connection = mc.getConnection();
		if (connection == null) return;
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
		IO.execute(() -> write(d, pos, fp, sections, bytes));
	}

	/** Every frame (render thread): stored chunks handed to the game within the budget; returns the positions given. */
	static void apply(Minecraft mc, ClientLevel level, java.util.function.LongConsumer kept, int radius) {
		if (openFor != level || READY.isEmpty() || mc.player == null) return;
		var connection = mc.getConnection();
		if (connection == null) return;
		long end = System.nanoTime() + APPLY_NANOS;
		int pcx = mc.player.chunkPosition().x(), pcz = mc.player.chunkPosition().z();
		ClientboundLevelChunkWithLightPacket packet;
		while (System.nanoTime() < end && (packet = READY.poll()) != null) {
			int x = packet.x(), z = packet.z();
			if (Math.max(Math.abs(x - pcx), Math.abs(z - pcz)) > radius || level.getChunkSource().hasChunk(x, z)) continue;
			try {
				connection.handleLevelChunkWithLight(packet);
			} catch (RuntimeException e) {
				System.out.println("[cadence] Keep Server Chunks: a stored chunk at " + x + ", " + z + " couldn't be read; the store for this dimension is off: " + e);
				READY.clear();
				dir = null;
				return;
			}
			kept.accept(ChunkPos.pack(x, z));
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
			System.out.println("[cadence] Keep Server Chunks: couldn't store a chunk: " + e);
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
			}
			System.out.println("[cadence] Keep Server Chunks: store trimmed from " + (before >> 20) + " to " + (total >> 20) + " MB");
		} catch (IOException | RuntimeException e) {
			// (a store that can't be trimmed is only bigger)
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
		long h = Block.BLOCK_STATE_REGISTRY.size();
		try {
			var biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
			for (var biome : biomes) h = h * 1_000_003L + String.valueOf(biomes.getKey(biome)).hashCode();
		} catch (RuntimeException e) {
			h = h * 31 + 7;
		}
		return h;
	}
}
