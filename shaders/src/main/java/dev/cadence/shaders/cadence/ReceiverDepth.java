package dev.cadence.shaders.cadence;

import java.util.Collection;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

/**
 * Cadence: which shadow casters can matter for what the camera can see. Shadow-map lookups happen only at receivers: surfaces
 * on screen and (volumetric light) points in the air in front of them, which lie in sections vanilla's occlusion search marks
 * visible, or in open-air sections inside the view. A caster matters only if it is sunward of some receiver: in light space
 * (u, v across the light, t along it) its footprint overlaps a receiver's and its most sunward point is not downstream of
 * that receiver's deepest point. Built as a coarse map: for every 8x8-block (u, v) cell, the deepest t of any receiver whose
 * footprint, widened by the lateral padding (shadow filter reach, raster and waving displacement: 32 blocks, upstream's audit
 * found under 21), covers it; plus a small padding along t.
 */
final class ReceiverDepth {
	private static final float CELL = 8, LATERAL = 32, ALONG = 4;
	private final Vector3f d, u = new Vector3f(), v = new Vector3f();
	private final float u0, v0;
	private final int nu, nv;
	private final float[] deepest;

	/** d: the direction light travels (normalized); receivers: section origins (16^3 boxes). */
	ReceiverDepth(Vector3f d, Collection<BlockPos> receivers) {
		this.d = new Vector3f(d).normalize();
		Vector3f helper = Math.abs(this.d.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
		this.d.cross(helper, u).normalize();
		this.d.cross(u, v).normalize();
		float umin = Float.MAX_VALUE, umax = -Float.MAX_VALUE, vmin = Float.MAX_VALUE, vmax = -Float.MAX_VALUE;
		for (BlockPos o : receivers) {
			float[] b = bounds(o);
			umin = Math.min(umin, b[0]); umax = Math.max(umax, b[1]); vmin = Math.min(vmin, b[2]); vmax = Math.max(vmax, b[3]);
		}
		if (receivers.isEmpty()) { umin = umax = vmin = vmax = 0; }
		u0 = umin - LATERAL - CELL;
		v0 = vmin - LATERAL - CELL;
		nu = (int) Math.ceil((umax + LATERAL + CELL - u0) / CELL) + 1;
		nv = (int) Math.ceil((vmax + LATERAL + CELL - v0) / CELL) + 1;
		deepest = new float[nu * nv];
		java.util.Arrays.fill(deepest, -Float.MAX_VALUE);
		for (BlockPos o : receivers) {
			float[] b = bounds(o);
			int i0 = cell(b[0] - LATERAL, u0), i1 = cell(b[1] + LATERAL, u0), j0 = cell(b[2] - LATERAL, v0), j1 = cell(b[3] + LATERAL, v0);
			float t = b[5] + ALONG;
			for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) {
				int k = i * nv + j;
				if (t > deepest[k]) deepest[k] = t;
			}
		}
	}

	/** Can a caster in this section shadow any receiver? */
	boolean matters(BlockPos origin) {
		float[] b = bounds(origin);
		int i0 = Math.max(0, cell(b[0], u0)), i1 = Math.min(nu - 1, cell(b[1], u0)), j0 = Math.max(0, cell(b[2], v0)), j1 = Math.min(nv - 1, cell(b[3], v0));
		for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) if (b[4] <= deepest[i * nv + j]) return true;
		return false;
	}

	private int cell(float x, float origin) {
		return (int) Math.floor((x - origin) / CELL);
	}

	/** Reused by bounds() (render thread; each result is read before the next call). */
	private final float[] scratch = new float[6];

	/** {umin, umax, vmin, vmax, tmin, tmax} of a section's box, in a reused array. */
	private float[] bounds(BlockPos o) {
		float[] r = scratch;
		r[0] = r[2] = r[4] = Float.MAX_VALUE;
		r[1] = r[3] = r[5] = -Float.MAX_VALUE;
		for (int c = 0; c < 8; c++) {
			float x = o.getX() + ((c & 1) != 0 ? 16 : 0), y = o.getY() + ((c & 2) != 0 ? 16 : 0), z = o.getZ() + ((c & 4) != 0 ? 16 : 0);
			float pu = u.x * x + u.y * y + u.z * z, pv = v.x * x + v.y * y + v.z * z, pt = d.x * x + d.y * y + d.z * z;
			r[0] = Math.min(r[0], pu); r[1] = Math.max(r[1], pu);
			r[2] = Math.min(r[2], pv); r[3] = Math.max(r[3], pv);
			r[4] = Math.min(r[4], pt); r[5] = Math.max(r[5], pt);
		}
		return r;
	}

	/** Receivers: the camera's visible sections, plus sections inside the camera frustum that hold only air. */
	static java.util.List<BlockPos> receivers(net.minecraft.client.renderer.LevelRenderer renderer, Level level,
		net.minecraft.client.renderer.culling.Frustum frustum, Iterable<?> allSections) {
		java.util.List<BlockPos> out = new java.util.ArrayList<>();
		for (var s : renderer.visibleSections()) out.add(s.getRenderOrigin());
		if (frustum == null) return out;
		for (Object o : allSections) {
			var s = (SectionRenderDispatcher.RenderSection) o;
			BlockPos p = s.getRenderOrigin();
			if (!frustum.isVisible(new net.minecraft.world.phys.AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 16, p.getY() + 16, p.getZ() + 16))) continue;
			var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
			int index = level.getSectionIndex(p.getY());
			boolean air = chunk == null || index < 0 || index >= chunk.getSections().length || chunk.getSections()[index].hasOnlyAir();
			if (air) out.add(p);
		}
		return out;
	}
}
