package dev.cadence.shaders.cadence.shadow;

import dev.cadence.shaders.cadence.render.ReceiverDepth;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;

/**
 * Cadence: compile only the shadow casters that can shadow something the camera can see (ReceiverDepth).
 *
 * <p>With a shader pack, the loader hands vanilla's extraction every section inside the light's view box, so all of them get
 * compiled and kept: at RD 16 that was 835 MB of chunk geometry against 101 MB without shaders, 420 MB of it caves under the
 * terrain that only shadow other caves. This filter skips a light-view section only when it is wholly underground (its top
 * below the terrain surface at its corners and centre), not one of the camera's sections, and ReceiverDepth says it is not
 * sunward of any receiver; it is then not scheduled (nor rescheduled when dirty). Surface sections are still all compiled
 * as upstream does, so terrain behind you is ready when you turn (vanilla's camera search can't see through an uncompiled
 * section, so skipping those would make terrain load in as you turn). Rendering is unchanged (upstream's
 * audited receiver culling still chooses what the shadow pass draws); an unneeded caster's mesh, stale or absent, never
 * decides a lookup any receiver makes. The receiver map is rebuilt every 10 frames, so when the view changes a newly needed
 * caster is scheduled within 10 frames, then compiled like any section (vanilla's own geometry pops in the same way).
 * -Dcadence.shadowCompileCull=false: compile every caster, as upstream.
 */
public final class ShadowCompileCull {
	public static final boolean ENABLED = !"false".equals(System.getProperty("cadence.shadowCompileCull"));
	private static final boolean LOG = Boolean.getBoolean("cadence.probe") || System.getProperty("cadence.selftest") != null;
	private static ReceiverDepth depth;
	/** Light-view sections this filter skips, by section node: underground and not needed by any receiver, or beyond the
	 * shadow proxy distance (rebuilt with the map; render thread). */
	private static it.unimi.dsi.fastutil.longs.LongOpenHashSet skipNodes = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/** skipNodes::contains, one instance per rebuild (the extraction caches its candidates by this identity). */
	private static java.util.function.LongPredicate skipTest = skipNodes::contains;
	/** The set the next rebuild fills (swapped with skipNodes when they differ), and the per-rebuild column surfaces. */
	private static it.unimi.dsi.fastutil.longs.LongOpenHashSet spareNodes = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap columnSurface = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(2048);
	private static int frames;
	private static long kept, dropped, logged;

	private ShadowCompileCull() {
	}

	/** One section further than the proxy distance: a section at the edge is still drawn for real while its proxy builds. */
	private static boolean farEnough(net.minecraft.world.phys.Vec3 cam, RenderSection s) {
		double dx = Math.max(Math.abs(s.getRenderOrigin().getX() + 8 - cam.x) - 8, 0), dz = Math.max(Math.abs(s.getRenderOrigin().getZ() + 8 - cam.z) - 8, 0);
		double d = dev.cadence.shaders.cadence.shadow.ShadowProxies.distance() + 16;
		return dx * dx + dz * dz > d * d;
	}

	// the view the receiver map was built for (rebuilt when it changes; it took 3-5 ms of the render thread every 10th frame)
	private static double builtX, builtY, builtZ;
	private static float builtLookX, builtLookY, builtLookZ, builtSunX, builtSunY, builtSunZ;
	private static final int MAX_FRAMES = 30;
	private static final double MOVE_SQ = 4 * 4;
	private static final float LOOK_COS = (float) Math.cos(Math.toRadians(5)), SUN_COS = (float) Math.cos(Math.toRadians(0.5));

	/**
	 * The receiver map and skip sets: rebuilt when the camera has moved 4 blocks or turned 5 degrees, the sun has moved half
	 * a degree, or 30 frames passed (sections loading in); false when there is nothing to filter with.
	 */
	private static boolean refresh(LevelRenderer renderer) {
		Minecraft mc = Minecraft.getInstance();
		var runtime = dev.cadence.shaders.runtime.ShaderRuntime.get();
		if (runtime == null || runtime.uniforms() == null || mc.level == null || renderer.viewArea() == null) return false;
		var camera = mc.gameRenderer.mainCamera();
		var at = camera.position();
		var look = camera.forwardVector();
		var sun = runtime.uniforms().shadowDirectionWorld();
		double mx = at.x - builtX, my = at.y - builtY, mz = at.z - builtZ;
		boolean changed = mx * mx + my * my + mz * mz > MOVE_SQ
			|| look.x() * builtLookX + look.y() * builtLookY + look.z() * builtLookZ < LOOK_COS
			|| sun.x() * builtSunX + sun.y() * builtSunY + sun.z() * builtSunZ < SUN_COS * sun.length();
		if (depth == null || ++frames >= MAX_FRAMES || changed) {
			frames = 0;
			builtX = at.x;
			builtY = at.y;
			builtZ = at.z;
			builtLookX = look.x();
			builtLookY = look.y();
			builtLookZ = look.z();
			float sunLength = Math.max(sun.length(), 1e-6f);
			builtSunX = sun.x() / sunLength;
			builtSunY = sun.y() / sunLength;
			builtSunZ = sun.z() / sunLength;
			var dir = sun;
			Iterable<?> all = sections(renderer.viewArea());
			if (all == null) return false;
			depth = new ReceiverDepth(new Vector3f(-dir.x(), -dir.y(), -dir.z()),
				ReceiverDepth.receivers(renderer, mc.level, mc.gameRenderer.mainCamera().getCullFrustum(), all));
			var nextNodes = spareNodes;
			nextNodes.clear();
			var proxies = dev.cadence.shaders.cadence.shadow.ShadowProxies.get();
			var cam = mc.gameRenderer.mainCamera().position();
			// the lowest of a column's 5 sampled surface heights, once per column (its sections share them)
			columnSurface.clear();
			for (Object o : all) {
				RenderSection s = (RenderSection) o;
				var origin = s.getRenderOrigin();
				long column = ((long) origin.getX() << 32) ^ (origin.getZ() & 0xffffffffL);
				int surface = columnSurface.getOrDefault(column, Integer.MIN_VALUE);
				if (surface == Integer.MIN_VALUE) {
					surface = surfaceMin(mc.level, origin.getX(), origin.getZ());
					columnSurface.put(column, surface);
				}
				if (origin.getY() + 16 <= surface && !depth.matters(origin)) nextNodes.add(s.getSectionNode());
				// Beyond the shadow proxy distance (+1 section) the shadow pass draws the column's proxy, not its sections.
				else if (proxies != null && proxies.far(cam.x, cam.z, s.getRenderOrigin().getX() >> 4, s.getRenderOrigin().getZ() >> 4)
					&& farEnough(cam, s)) nextNodes.add(s.getSectionNode());
			}
			if (!nextNodes.equals(skipNodes)) {
				// the old set's predicate is only compared by identity from now on, so the set can be refilled next time
				spareNodes = skipNodes;
				skipNodes = nextNodes;
				skipTest = nextNodes::contains;
			}
		}
		return true;
	}

	/** This frame's refresh already ran (excludedNodes, before the extraction). */
	private static boolean refreshed;

	/**
	 * Before the dirty-section extraction, once per frame: the section nodes filter() will drop unless they are camera
	 * sections (the extraction adds those from the camera's list regardless), so it can skip them up front. null when off.
	 */
	public static java.util.function.LongPredicate excludedNodes(LevelRenderer renderer) {
		if (!ENABLED || !refresh(renderer)) return null;
		refreshed = true;
		return skipTest;
	}

	/** Called with the camera's list and the loader's extraction list (camera + light-view sections); render thread. */
	public static ObjectArrayList<RenderSection> filter(LevelRenderer renderer, List<RenderSection> camera, ObjectArrayList<RenderSection> selected) {
		boolean ready = refreshed;
		refreshed = false;
		if (!ENABLED || selected.size() == camera.size()) return selected;
		if (!ready && !refresh(renderer)) return selected;
		// the camera's set is only needed for sections in the skip set (built on the first one)
		Set<RenderSection> own = null;
		ObjectArrayList<RenderSection> out = new ObjectArrayList<>(selected.size());
		for (RenderSection s : selected) {
			boolean keep = !skipNodes.contains(s.getSectionNode());
			if (!keep) {
				if (own == null) {
					own = Collections.newSetFromMap(new IdentityHashMap<>(camera.size() * 2));
					own.addAll(camera);
				}
				keep = own.contains(s);
			}
			if (keep) {
				out.add(s);
				kept++;
			} else {
				dropped++;
			}
		}
		if (LOG && System.nanoTime() - logged > 30_000_000_000L) {
			logged = System.nanoTime();
			LoggerFactory.getLogger("cadence").info("Shadow compile cull: kept {} / dropped {} light-view section entries so far", kept, dropped);
		}
		return out;
	}

	private static long diagFrames, diagDrawn, diagUnneeded;

	/** Diagnostic: of the sections the shadow pass is about to draw, how many the receiver map says can't matter. */
	public static void countShadowSelection(java.util.List<RenderSection> drawn) {
		ReceiverDepth d = depth;
		if (d == null) return;
		int unneeded = 0;
		for (RenderSection s : drawn) if (!d.matters(s.getRenderOrigin())) unneeded++;
		diagDrawn += drawn.size();
		diagUnneeded += unneeded;
		if (++diagFrames % 300 == 0) {
			LoggerFactory.getLogger("cadence").info("Shadow selection: {} sections drawn per frame, {} of them can't shadow a receiver",
				diagDrawn / 300, diagUnneeded / 300);
			diagDrawn = 0;
			diagUnneeded = 0;
		}
	}

	/**
	 * The lowest WORLD_SURFACE height at a section column's four corner columns and its centre: a section is underground when
	 * its top is at or below it.
	 */
	private static int surfaceMin(net.minecraft.world.level.Level level, int x, int z) {
		var type = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE;
		return Math.min(Math.min(Math.min(level.getHeight(type, x, z), level.getHeight(type, x + 15, z)),
			Math.min(level.getHeight(type, x, z + 15), level.getHeight(type, x + 15, z + 15))), level.getHeight(type, x + 8, z + 8));
	}

	/** A pack (re)load or a world change: the next call rebuilds the receiver map. */
	public static void reset() {
		depth = null;
	}

	private static Field sectionsField;

	static Iterable<?> sections(ViewArea area) {
		try {
			if (sectionsField == null) {
				sectionsField = ViewArea.class.getDeclaredField("sections");
				sectionsField.setAccessible(true);
			}
			return (Iterable<?>) sectionsField.get(area);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}
}
