package dev.cadence.shaders.cadence.render;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/**
 * Cadence diagnostic (self-test step "occlusion"): how much of what the camera draws is hidden behind opaque terrain, the
 * most a GPU occlusion test (Hi-Z) could remove. For each section in the camera's draw list, rays from the eye to its 8
 * corners and its centre are walked block by block (Amanatides-Woo); the section counts as hidden when every ray meets an
 * opaque full block (isSolidRender) before reaching it. An estimate (9 rays, not a full coverage test), only for deciding
 * whether to build Hi-Z. Render thread.
 */
public final class OcclusionEstimate {
	private OcclusionEstimate() {
	}

	public static void run(Minecraft mc) {
		if (mc.level == null) return;
		var renderer = mc.levelRenderer;
		Vec3 eye = mc.gameRenderer.mainCamera().position();
		int sections = 0, hidden = 0;
		long quads = 0, hiddenQuads = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (RenderSection s : renderer.visibleSections()) {
			var mesh = s.getSectionMesh();
			int q = 0;
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				var draw = mesh.getSectionDraw(layer);
				if (draw != null) q += draw.indexCount() / 6;
			}
			if (q == 0) continue;
			sections++;
			quads += q;
			BlockPos o = s.getRenderOrigin();
			boolean allBlocked = true;
			for (int i = 0; i < 9 && allBlocked; i++) {
				double tx = o.getX() + (i == 8 ? 8 : (i & 1) * 16), ty = o.getY() + (i == 8 ? 8 : (i >> 1 & 1) * 16),
					tz = o.getZ() + (i == 8 ? 8 : (i >> 2 & 1) * 16);
				// pull corners 0.01 inside so the ray ends in the section, not on a neighbour's face
				tx += tx > o.getX() + 8 ? -0.01 : 0.01;
				ty += ty > o.getY() + 8 ? -0.01 : 0.01;
				tz += tz > o.getZ() + 8 ? -0.01 : 0.01;
				if (!blocked(mc, eye, tx, ty, tz, o, pos)) allBlocked = false;
			}
			if (allBlocked) {
				hidden++;
				hiddenQuads += q;
			}
		}
		LoggerFactory.getLogger("cadence-selftest").info(String.format(Locale.ROOT,
			"[selftest] occlusion estimate: %d of %d drawn sections hidden behind opaque blocks (%.0f%%), %d of %d quads (%.0f%%)",
			hidden, sections, sections == 0 ? 0.0 : 100.0 * hidden / sections, hiddenQuads, quads, quads == 0 ? 0.0 : 100.0 * hiddenQuads / quads));
	}

	/** Whether an opaque full block lies on the segment eye → target before the target's section. */
	private static boolean blocked(Minecraft mc, Vec3 eye, double tx, double ty, double tz, BlockPos section, BlockPos.MutableBlockPos pos) {
		double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
		int x = (int) Math.floor(eye.x), y = (int) Math.floor(eye.y), z = (int) Math.floor(eye.z);
		int ex = (int) Math.floor(tx), ey = (int) Math.floor(ty), ez = (int) Math.floor(tz);
		int sx = Integer.signum((int) Math.signum(dx)), sy = (int) Math.signum(dy), sz = (int) Math.signum(dz);
		double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 : x) - eye.x) / dx;
		double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 : y) - eye.y) / dy;
		double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 : z) - eye.z) / dz;
		double tDX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx), tDY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy),
			tDZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
		for (int steps = 0; steps < 1024; steps++) {
			if (x == ex && y == ey && z == ez) return false;
			// inside the target section: reached it
			if (x >> 4 == section.getX() >> 4 && y >> 4 == section.getY() >> 4 && z >> 4 == section.getZ() >> 4) return false;
			if (steps > 0) {
				pos.set(x, y, z);
				var state = mc.level.getBlockState(pos);
				if (state.isSolidRender()) return true;
			}
			if (tMaxX < tMaxY && tMaxX < tMaxZ) {
				x += sx;
				tMaxX += tDX;
			} else if (tMaxY < tMaxZ) {
				y += sy;
				tMaxY += tDY;
			} else {
				z += sz;
				tMaxZ += tDZ;
			}
		}
		return false;
	}
}
