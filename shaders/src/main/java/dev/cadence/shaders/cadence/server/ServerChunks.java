package dev.cadence.shaders.cadence.server;

import dev.cadence.shaders.cadence.ui.ShaderToggle;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;

/**
 * Video Settings > Keep Server Chunks. A server sends the chunks within its own view distance and tells the client to forget
 * each one the player leaves behind; with a render distance past the server's, the rest of the view stayed empty (the game
 * also held the render distance to the server's). On (the default): the client's chunk storage is as big as the player's render
 * distance, a chunk the server forgets is kept while it is within that distance (as it was last sent: the server sends it again,
 * up to date, when the player comes back near), and the server's cap on the render distance is lifted. Far terrain still reaches
 * past it. Kept chunks are let go once farther than the render distance, before the storage moves off them, so their light data
 * goes too. Not in single player or on a LAN host (the integrated server already sends the whole render distance); off with
 * Bobby installed (it does the same).
 */
public final class ServerChunks {
	private static volatile boolean on = !"false".equalsIgnoreCase(ShaderToggle.configValue("keepServerChunks", "true").strip())
		&& !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("bobby");
	/** The server's view distance as it sent it (-1 before any), and the storage radius applied for it in appliedIn. */
	private static int serverRadius = -1, applied = -1;
	private static ClientLevel appliedIn;
	/** Whether Options' server render distance was last set with the cap lifted (0), so a change of the setting puts it back (BH5-05). */
	private static boolean capLifted;
	private static final LongOpenHashSet KEPT = new LongOpenHashSet();
	private static ClientLevel keptIn;
	/** A forget Cadence itself asks for (a kept chunk let go): vanilla's path, not kept again. */
	private static boolean releasing;
	private static long nextCheck;

	private ServerChunks() {
	}

	public static boolean on() {
		return on;
	}

	public static void toggle() {
		on = !on;
		ShaderToggle.saveKey("keepServerChunks", Boolean.toString(on));
		nextCheck = 0;
	}

	public static int kept() {
		return KEPT.size();
	}

	/** Whether this connection is to a real server (not single player or a LAN host). */
	private static boolean remote(Minecraft mc) {
		return !mc.isLocalServer();
	}

	private static boolean active(Minecraft mc) {
		return on && remote(mc);
	}

	/** The player's own render distance (the option, not the effective distance: that is what is lifted here). */
	private static int playerDistance(Minecraft mc) {
		return mc.options.renderDistance().get();
	}

	/** ClientChunkCache.updateViewRadius (ServerChunksCacheMixin): the server's radius, raised to the player's distance. */
	public static int storageRadius(int radius) {
		serverRadius = radius;
		Minecraft mc = Minecraft.getInstance();
		int r = active(mc) ? Math.max(radius, playerDistance(mc)) : radius;
		applied = r;
		appliedIn = mc.level;
		return r;
	}

	/**
	 * Options.setServerRenderDistance (ServerChunksOptionsMixin; the login and view-distance packets, both with the server's chunk
	 * radius): the server's cap, none while chunks are kept.
	 */
	public static int serverRenderDistance(int distance) {
		if (distance > 0) serverRadius = distance;
		capLifted = distance > 0 && active(Minecraft.getInstance());
		return capLifted ? 0 : distance;
	}

	/** ClientPacketListener.handleForgetLevelChunk, on the render thread: true keeps the chunk (the forget is skipped). */
	public static boolean keep(ChunkPos pos) {
		if (releasing) return false;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || !active(mc) || mc.player == null) return false;
		if (keptIn != level) {
			KEPT.clear();
			keptIn = level;
		}
		int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z(), rd = playerDistance(mc);
		if (Math.max(Math.abs(pos.x() - cx), Math.abs(pos.z() - cz)) > rd) return false;
		KEPT.add(ChunkPos.pack(pos.x(), pos.z()));
		return true;
	}

	/** ClientChunkCache.replaceWithPacketData: the server sent the chunk again; it is live, not kept. */
	public static void sent(int x, int z) {
		if (!KEPT.isEmpty()) KEPT.remove(ChunkPos.pack(x, z));
	}

	/**
	 * ClientChunkCache.updateViewCenter, before the storage moves: kept chunks farther than the render distance from the new
	 * centre are let go now, while the storage still holds them (afterwards it couldn't find them to drop).
	 */
	public static void centreMoving(int x, int z) {
		if (KEPT.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		release(mc, x, z, active(mc) ? playerDistance(mc) : -1);
	}

	/** Every frame (FramerateCapMixin), at most once a second: the setting or the render distance changed, a world closed. */
	public static void check() {
		long now = System.nanoTime();
		if (now < nextCheck) return;
		nextCheck = now + 1_000_000_000L;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) {
			KEPT.clear();
			keptIn = null;
			serverRadius = applied = -1;
			appliedIn = null;
			capLifted = false;
			return;
		}
		if (keptIn != null && keptIn != level) {
			KEPT.clear();
			keptIn = null;
		}
		if (serverRadius < 0 || !remote(mc)) return;
		int want = active(mc) ? Math.max(serverRadius, playerDistance(mc)) : serverRadius;
		if (mc.player != null && !KEPT.isEmpty())
			release(mc, mc.player.chunkPosition().x(), mc.player.chunkPosition().z(), active(mc) ? playerDistance(mc) : -1);
		if (want != applied || appliedIn != level || capLifted != active(mc)) {
			int server = serverRadius;
			level.getChunkSource().updateViewRadius(server);  // (raised by storageRadius)
			mc.options.setServerRenderDistance(server);  // (the cap: lifted or put back)
			if (active(mc) || want != server)
				System.out.println("[cadence] Keep Server Chunks: chunk storage for " + applied + " chunks (the server sends " + server + ")");
		}
	}

	/** Kept chunks farther than rd from (x, z) (rd < 0: all of them) forgotten as the server asked, through vanilla's path. */
	private static void release(Minecraft mc, int x, int z, int rd) {
		var connection = mc.getConnection();
		if (connection == null) {
			KEPT.clear();
			return;
		}
		releasing = true;
		try {
			for (LongIterator it = KEPT.iterator(); it.hasNext();) {
				ChunkPos pos = ChunkPos.unpack(it.nextLong());
				if (rd >= 0 && Math.max(Math.abs(pos.x() - x), Math.abs(pos.z() - z)) <= rd) continue;
				it.remove();
				connection.handleForgetLevelChunk(new ClientboundForgetLevelChunkPacket(pos));
			}
		} finally {
			releasing = false;
		}
	}
}
