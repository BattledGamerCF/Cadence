package dev.cadence.metal.lod;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBeamOwner;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Far terrain's beacon beams, as Distant Horizons draws them: every beacon the game draws (or that a loading or unloading
 * chunk holds) is remembered with its beam and kept with the far terrain's tiles on disk; where the game doesn't draw it (past
 * the render distance) it is drawn here with the game's own beam (BeaconRenderer, so shader packs draw it as theirs), out to the
 * far terrain's reach, widened with distance as the game widens near ones so a beam kilometres away is still a line on
 * screen. Render thread.
 */
final class LodBeacons {
	/** A beacon: its block, and its beam's sections bottom up (colour and height each). */
	private static final class Beacon {
		final int x, y, z;
		final int[] colors, heights;
		/** The frame the game last drew it (its renderer extracted it): then it isn't drawn here. */
		long drawnFrame = -1;

		Beacon(int x, int y, int z, int[] colors, int[] heights) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.colors = colors;
			this.heights = heights;
		}
	}

	private static final BlockPos.MutableBlockPos PROBE = new BlockPos.MutableBlockPos();

	/** Frames counted by submit (the game's extraction of a frame comes before its submission). */
	private static long frame;

	/** -Dcadence.lod.beaconLog: log beacons as they're remembered, and how many are drawn (every 10 s). */
	private static final boolean LOG = Boolean.getBoolean("cadence.lod.beaconLog");
	private static long logged;
	private static final Long2ObjectOpenHashMap<Beacon> BEACONS = new Long2ObjectOpenHashMap<>();
	private static @Nullable Path file;
	private static boolean dirty;

	private LodBeacons() {
	}

	/** A new world or dimension: the last one's beacons saved, this one's loaded from its far terrain cache folder. */
	static void world(@Nullable Path cacheDir) {
		save();
		BEACONS.clear();
		file = cacheDir == null ? null : cacheDir.resolve("beacons.bin");
		if (file == null || !Files.isRegularFile(file)) return;
		try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
			int n = in.readInt();
			for (int i = 0; i < n && i < 1 << 16; i++) {
				int x = in.readInt(), y = in.readInt(), z = in.readInt(), sections = Math.min(in.readUnsignedByte(), 64);
				int[] colors = new int[sections], heights = new int[sections];
				for (int k = 0; k < sections; k++) {
					colors[k] = in.readInt();
					heights[k] = in.readInt();
				}
				BEACONS.put(BlockPos.asLong(x, y, z), new Beacon(x, y, z, colors, heights));
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("cadence-lod: beacons not read: " + e);
		}
	}

	static void save() {
		Path f = file;
		if (f == null || !dirty) return;
		dirty = false;
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling("beacons.bin.tmp");
			try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
				out.writeInt(BEACONS.size());
				for (Beacon b : BEACONS.values()) {
					out.writeInt(b.x);
					out.writeInt(b.y);
					out.writeInt(b.z);
					out.writeByte(b.colors.length);
					for (int k = 0; k < b.colors.length; k++) {
						out.writeInt(b.colors[k]);
						out.writeInt(b.heights[k]);
					}
				}
			}
			Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			System.out.println("cadence-lod: beacons not saved: " + e);
		}
	}

	/** A chunk loading or unloading: its beacons as they are now (a beacon gone or unlit is forgotten). */
	static void chunk(LevelChunk chunk) {
		int cx = chunk.getPos().x(), cz = chunk.getPos().z();
		// (this chunk's old entries: few beacons exist, a scan is cheaper than an index)
		if (!BEACONS.isEmpty()) {
			var it = BEACONS.values().iterator();
			while (it.hasNext()) {
				Beacon b = it.next();
				if (b.x >> 4 == cx && b.z >> 4 == cz && !(chunk.getBlockEntity(new BlockPos(b.x, b.y, b.z)) instanceof BeaconBeamOwner)) {
					it.remove();
					dirty = true;
				}
			}
		}
		for (BlockEntity be : chunk.getBlockEntities().values()) seen(be);
	}

	/** A beacon as it is now (the game drew it, or its chunk loaded or unloaded); not lit or not worked out yet: kept as last seen. */
	static void seen(BlockEntity be) {
		if (!(be instanceof BeaconBeamOwner owner)) return;
		List<BeaconBeamOwner.Section> sections = owner.getBeamSections();
		BlockPos p = be.getBlockPos();
		if (sections.isEmpty()) return;
		Beacon known = BEACONS.get(p.asLong());
		if (known != null && known.colors.length == sections.size()) {
			// (asked every frame for every beacon drawn: nothing allocated while it stays the same)
			boolean same = true;
			for (int k = 0; k < sections.size() && same; k++)
				same = known.colors[k] == sections.get(k).getColor() && known.heights[k] == sections.get(k).getHeight();
			known.drawnFrame = frame;
			if (same) return;
		}
		int[] colors = new int[sections.size()], heights = new int[sections.size()];
		for (int k = 0; k < colors.length; k++) {
			colors[k] = sections.get(k).getColor();
			heights[k] = sections.get(k).getHeight();
		}
		Beacon now = new Beacon(p.getX(), p.getY(), p.getZ(), colors, heights);
		now.drawnFrame = frame;
		Beacon old = BEACONS.put(p.asLong(), now);
		if (LOG && old == null) System.out.println("cadence-lod: beacon at " + p.toShortString() + " (" + colors.length + " sections)");
		if (old == null || !java.util.Arrays.equals(old.colors, colors) || !java.util.Arrays.equals(old.heights, heights)) dirty = true;
	}

	/** The level's block entities have been submitted: the remembered beacons whose chunks aren't loaded, within reach. */
	static void submit(PoseStack pose, LevelRenderState state, SubmitNodeCollector collector, ClientLevel level, float reach) {
		long thisFrame = frame++;
		if (BEACONS.isEmpty() || reach <= 0 || state.cameraRenderState == null) return;
		Vec3 cam = state.cameraRenderState.pos;
		float time = Math.floorMod(state.gameTime, 40) + state.worldPartialTicks;
		double reach2 = (double) reach * reach;
		int drawn = 0, loaded = 0;
		for (var it = BEACONS.values().iterator(); it.hasNext();) {
			Beacon b = it.next();
			if (b.drawnFrame == thisFrame) {
				loaded++;
				continue;  // (the game draws it)
			}
			// (the chunk cache's own lookup: ClientLevel.hasChunk is always true, and an absent chunk reads as air)
			var chunk = level.getChunkSource().getChunkNow(b.x >> 4, b.z >> 4);
			if (chunk != null) {
				// its chunk is here (only too far for the game to draw it): what stands there now decides. Broken (no beacon block):
				// forgotten; a beacon without a beam right now (its pyramid gone): not drawn. It used to keep its beam for as long
				// as the chunk stayed loaded (BH2-04). (The block, not the block entity: a chunk being unloaded has lost its block
				// entities but not its blocks)
				if (!chunk.getBlockState(PROBE.set(b.x, b.y, b.z)).is(Blocks.BEACON)) {
					it.remove();
					dirty = true;
					continue;
				}
				if (chunk.getBlockEntity(PROBE) instanceof BeaconBeamOwner owner && owner.getBeamSections().isEmpty()) continue;
			}
			drawn++;
			double dx = b.x + 0.5 - cam.x, dz = b.z + 0.5 - cam.z, d2 = dx * dx + dz * dz;
			if (d2 > reach2) continue;
			float scale = Math.max(1.0F, (float) Math.sqrt(d2) / 96.0F);
			pose.pushPose();
			pose.translate(b.x - cam.x, b.y - cam.y, b.z - cam.z);
			int y = 0;
			for (int k = 0; k < b.colors.length; k++) {
				int height = k == b.colors.length - 1 ? 2048 : b.heights[k];
				BeaconRenderer.submitBeaconBeam(pose, collector, BeaconRenderer.BEAM_LOCATION, 1.0F, time, y, height, b.colors[k], 0.2F * scale, 0.25F * scale);
				y += b.heights[k];
			}
			pose.popPose();
		}
		if (LOG && System.nanoTime() - logged > 10_000_000_000L) {
			logged = System.nanoTime();
			System.out.println("cadence-lod: beacons " + BEACONS.size() + ", drawn far " + drawn + ", drawn by the game " + loaded);
		}
	}
}
