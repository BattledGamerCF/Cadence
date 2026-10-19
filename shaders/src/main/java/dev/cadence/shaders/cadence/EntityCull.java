package dev.cadence.shaders.cadence;

import dev.cadence.shaders.geometry.FeatureDrawContext;
import dev.cadence.shaders.runtime.ShadowRenderer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.slf4j.LoggerFactory;

/**
 * Cadence: entities and block entities are drawn only where they can be seen.
 *
 * <p><b>Behind walls</b> (with and without a pack): vanilla draws every entity inside the camera's view, also one in a cave
 * under the floor or behind a hill. Vanilla's own camera search (SectionOcclusionGraph, the flood from the camera's section
 * through open section faces that decides which terrain is drawn) already knows which sections can be seen at all; an
 * entity is skipped when none of the sections its box touches was reached. That is the test the terrain itself passes, so an
 * entity is hidden exactly where the ground around it isn't drawn either. Never skipped: glowing entities (seen through
 * walls), the camera entity, its vehicle and passengers, entities that opt out of culling, boxes taller or wider than three
 * sections, entities outside the build height or the view area.
 *
 * <p><b>Shadow pass</b> (with a pack): the loader extracted every entity inside the light's view box (all around the player,
 * out to the shadow distance) and drew each in both passes, and every block entity of the light's sections too. Each now
 * goes to the camera pass only if the camera can see it (as vanilla decides, plus the test above) and to the shadow pass
 * only if the shadow pass's own section test (light box + upstream's audited receiver volume, ShadowRenderer.casterTest)
 * accepts its box; one in neither is not extracted at all.
 *
 * <p>-Dcadence.entityCull=false: both off (as before). Switchable at runtime ({@link #set}, self-test entitycull=).
 */
public final class EntityCull {
	private static volatile boolean enabled = !"false".equals(System.getProperty("cadence.entityCull"));
	private static final boolean LOG = Boolean.getBoolean("cadence.probe") || System.getProperty("cadence.selftest") != null;

	// per extraction (render thread)
	private static Predicate<AABB> caster;
	private static LongOpenHashSet cameraSections;
	/** The set cameraSections points to once built, reused frame to frame (cleared, not reallocated). */
	private static final LongOpenHashSet cameraSectionSet = new LongOpenHashSet();
	// blockEntityPasses' answers {camera, shadow} (read-only for the caller)
	private static final boolean[] CAMERA_ONLY = {true, false}, SHADOW_ONLY = {false, true}, NEITHER = {false, false};
	private static boolean packFrame, reuseFrame;
	/** Particles: per section, 1 = reached by the camera search, 2 = not (this extraction). */
	private static final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap reached = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
	private static long particlesSeen, particlesHidden;
	/**
	 * In front of reached: the last answers by section, direct-mapped (a frame's particles are in a few sections: a hash map
	 * lookup per particle was 5.7% of the render thread in a scene with a few thousand). An entry counts only with this
	 * extraction's stamp.
	 */
	private static final long[] recentKey = new long[64];
	private static final byte[] recentAnswer = new byte[64];
	private static final int[] recentAt = new int[64];
	private static int recentStamp = 1;
	private static boolean pendingCamera = true, pendingShadow = true, pending;
	private static long frames, seen, hidden, cameraOnly, shadowOnly, dropped, beSeen, beCameraOnly, beShadowOnly, beDropped, logged;

	private EntityCull() {
	}

	public static boolean enabled() {
		return enabled;
	}

	public static void set(boolean on) {
		enabled = on;
	}

	/** LevelExtractor.extract HEAD: the shadow caster test and the camera's sections for this extraction. */
	public static void beginExtraction(LevelRenderer renderer, boolean pack) {
		caster = null;
		cameraSections = null;
		packFrame = false;
		reuseFrame = false;
		if (!reached.isEmpty()) reached.clear();
		recentStamp++;
		pending = false;
		if (!enabled) return;
		frames++;
		if (LOG && System.nanoTime() - logged > 30_000_000_000L) {
			logged = System.nanoTime();
			LoggerFactory.getLogger("cadence").info(
				"Entity cull: {} frames; entities {} in view, {} behind walls, {} camera-only, {} shadow-only, {} skipped; block entities {}, {} camera-only, {} shadow-only, {} skipped",
				frames, seen, hidden, cameraOnly, shadowOnly, dropped, beSeen, beCameraOnly, beShadowOnly, beDropped);
			LoggerFactory.getLogger("cadence").info("Particle cull: {} in view, {} behind walls; physics: {} moves in open air, {} searched",
				particlesSeen, particlesHidden, ParticlePhysics.skipped, ParticlePhysics.searched);
		}
		if (!pack) return;
		caster = ShadowRenderer.casterTest();
		packFrame = true;
		reuseFrame = ShadowReuse.predictReuse();
	}

	/**
	 * Around LevelExtractor.isEntityVisible: {@code visible} is vanilla's answer for {@code frustum} (the camera's, or the
	 * loader's camera+light union). Returns the answer with the cull applied; remembers the entity's passes for
	 * {@link #extracted}.
	 */
	public static boolean decide(Entity entity, Frustum frustum, float partial, boolean visible) {
		pending = false;
		if (!visible || !enabled) return visible;
		Minecraft mc = Minecraft.getInstance();
		AABB box = cullBox(mc, entity, partial);
		seen++;
		if (frustum instanceof ShadowRenderer.UnionFrustum union) {
			// A renderer that answered without asking the frustum (no culling, ...) keeps both passes.
			if (!union.asked || box == null) return true;
			boolean camera = union.cameraHit;
			if (camera && behindWalls(mc, entity, box)) {
				camera = false;
				hidden++;
			}
			// A frame that keeps last frame's shadow map (ShadowReuse) draws no shadows: nothing to extract for it.
			boolean shadow = union.lightHit && !reuseFrame && (caster == null || caster.test(box));
			if (!camera && !shadow) {
				dropped++;
				return false;
			}
			if (camera != shadow) {
				if (camera) cameraOnly++;
				else shadowOnly++;
				pendingCamera = camera;
				pendingShadow = shadow;
				pending = true;
			}
			return true;
		}
		if (box != null && behindWalls(mc, entity, box)) {
			hidden++;
			return false;
		}
		return true;
	}

	/** LevelExtractor.extractEntity RETURN: the passes {@link #decide} chose for this entity. */
	public static void extracted(EntityRenderState state) {
		if (!pending || state == null) return;
		pending = false;
		if (!pendingCamera) FeatureDrawContext.shadowOnlyEntity(state);
		else if (!pendingShadow) FeatureDrawContext.cameraOnlyEntity(state);
	}

	/** Block entity extraction with a pack: {camera, shadow}, or null when no change applies (draw in both). */
	public static boolean[] blockEntityPasses(BlockPos pos, boolean globallyRendered) {
		if (!enabled || !packFrame || globallyRendered) return null;
		if (cameraSections == null) {
			// Built at the first block entity: by then this extraction has applied the camera's frustum (visibleSections is
			// the list vanilla draws block entities from without a pack).
			var visible = Minecraft.getInstance().levelRenderer.visibleSections();
			LongOpenHashSet set = cameraSectionSet;
			set.clear();
			for (RenderSection s : visible) set.add(s.getSectionNode());
			cameraSections = set;
		}
		boolean camera = cameraSections.contains(SectionPos.asLong(pos));
		int x = pos.getX() & ~15, y = pos.getY() & ~15, z = pos.getZ() & ~15;
		// The section's box, as the shadow pass tests terrain sections (a block entity model stays near its block).
		boolean shadow = !reuseFrame && (caster == null || caster.test(new AABB(x, y, z, x + 16, y + 16, z + 16)));
		beSeen++;
		if (camera && shadow) return null;
		if (camera) beCameraOnly++;
		else if (shadow) beShadowOnly++;
		else beDropped++;
		return camera ? CAMERA_ONLY : shadow ? SHADOW_ONLY : NEITHER;
	}

	/**
	 * The box vanilla's frustum test uses (the renderer's culling box, inflated 0.5; a NaN or empty one: 2 blocks around the
	 * entity), or null when the renderer opts out of culling or the entity is leashed (its rope reaches the holder).
	 */
	private static AABB cullBox(Minecraft mc, Entity entity, float partial) {
		if (entity instanceof net.minecraft.world.entity.Leashable leashable && leashable.getLeashHolder() != null) return null;
		var renderer = (dev.cadence.shaders.cadence.mixin.EntityRendererCullInvoker) mc.getEntityRenderDispatcher().getRenderer(entity);
		if (!renderer.cadence$affectedByCulling(entity)) return null;
		AABB box = renderer.cadence$cullBox(entity, partial).inflate(0.5);
		if (box.hasNaN() || box.getSize() == 0)
			box = new AABB(entity.getX() - 2, entity.getY() - 2, entity.getZ() - 2, entity.getX() + 2, entity.getY() + 2, entity.getZ() + 2);
		return box;
	}

	/**
	 * A particle inside the camera's view (vanilla's point test passed): false when vanilla's camera search didn't reach its
	 * section (the terrain around it isn't drawn either). Particles keep ticking as before; only drawing is skipped.
	 */
	public static boolean particleVisible(double x, double y, double z) {
		if (!enabled) return true;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return true;
		int sx = SectionPos.blockToSectionCoord(x), sy = SectionPos.blockToSectionCoord(y), sz = SectionPos.blockToSectionCoord(z);
		long key = SectionPos.asLong(sx, sy, sz);
		int slot = (sx * 31 + sy * 7 + sz * 131) & 63;
		byte r = recentAt[slot] == recentStamp && recentKey[slot] == key ? recentAnswer[slot] : reached.get(key);
		if (r == 0) {
			r = 1;
			var area = mc.levelRenderer.viewArea();
			if (area != null && sy >= mc.level.getMinSectionY() && sy <= mc.level.getMaxSectionY()) {
				try {
					RenderSection s = area.getRenderSectionAt(new BlockPos(sx << 4, sy << 4, sz << 4));
					if (s != null && mc.levelRenderer.sectionOcclusionGraph().getNode(s) == null) r = 2;
				} catch (RuntimeException e) {
					r = 1;
				}
			}
			reached.put(key, r);
		}
		recentKey[slot] = key;
		recentAnswer[slot] = r;
		recentAt[slot] = recentStamp;
		particlesSeen++;
		if (r == 2) {
			particlesHidden++;
			return false;
		}
		return true;
	}

	/** True when vanilla's camera search reached none of the sections the entity's box touches. */
	static boolean behindWalls(Minecraft mc, Entity entity, AABB box) {
		if (mc.shouldEntityAppearGlowing(entity)) return false;
		Entity cameraEntity = mc.getCameraEntity();
		if (cameraEntity != null && (entity == cameraEntity || entity.hasIndirectPassenger(cameraEntity)
			|| cameraEntity.hasIndirectPassenger(entity))) return false;
		LevelRenderer renderer = mc.levelRenderer;
		var area = renderer.viewArea();
		if (area == null || mc.level == null) return false;
		int x0 = SectionPos.blockToSectionCoord(box.minX), x1 = SectionPos.blockToSectionCoord(box.maxX);
		int y0 = SectionPos.blockToSectionCoord(box.minY), y1 = SectionPos.blockToSectionCoord(box.maxY);
		int z0 = SectionPos.blockToSectionCoord(box.minZ), z1 = SectionPos.blockToSectionCoord(box.maxZ);
		if (x1 - x0 > 2 || y1 - y0 > 2 || z1 - z0 > 2) return false;
		int minY = mc.level.getMinSectionY(), maxY = mc.level.getMaxSectionY();
		if (y0 < minY || y1 > maxY) return false;
		try {
			var graph = renderer.sectionOcclusionGraph();
			BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
			for (int x = x0; x <= x1; x++)
				for (int y = y0; y <= y1; y++)
					for (int z = z0; z <= z1; z++) {
						RenderSection s = area.getRenderSectionAt(at.set(x << 4, y << 4, z << 4));
						if (s == null || graph.getNode(s) != null) return false;
					}
		} catch (RuntimeException e) {
			return false; // no search yet (world loading): draw
		}
		return true;
	}
}
