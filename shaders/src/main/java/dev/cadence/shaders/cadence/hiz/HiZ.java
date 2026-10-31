package dev.cadence.shaders.cadence.hiz;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.cadence.shaders.runtime.ShaderRenderPass;
import dev.cadence.shaders.runtime.ShaderRuntime;
import java.util.List;
import java.util.Map;
import dev.cadence.metal.backend.GpuCull;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4f;
import org.slf4j.LoggerFactory;

/**
 * Cadence: two-phase GPU occlusion culling of the shader pack's main terrain pass (native/mccull.m).
 *
 * <p>The camera view's opaque terrain (SOLID + CUTOUT) is drawn in two halves from the same indirect commands. Phase 1
 * draws the sections that were visible last frame; then the pass is suspended (its depth stored), the depth reduced to a
 * max-depth pyramid, and every section's 16³ box tested against it; phase 2 draws the sections now found visible that
 * phase 1 didn't draw. A section is skipped only when its whole box is behind depth already drawn this frame, so nothing
 * pops in; vanilla's section flood can't see this (open terrain connects everything behind a hill). Costs two pass
 * suspensions (tile memory stored and reloaded). Shader packs only (the vanilla pass can't be suspended).
 * -Dcadence.hiz=false: off; switchable at runtime (self-test hiz=, ab=hiz).
 */
public final class HiZ {
	public static volatile boolean enabled = !"false".equals(System.getProperty("cadence.hiz"));
	/**
	 * The same two phases in the vanilla terrain pass (no shader pack): phase 1 is set up in the pre command buffer, the pass
	 * is split once after it (the backend ends the render encoder, builds the pyramid, tests the rest, reopens the pass with its
	 * attachments loaded back and binds its state again). Reversed depth (26.x clears to 0). -Dcadence.hizVanilla=true/false.
	 */
	public static volatile boolean vanilla = Boolean.parseBoolean(System.getProperty("cadence.hizVanilla", "false"));
	/** The level projection as drawn this frame (HiZProjectionMixin). */
	private static final Matrix4f LEVEL_PROJECTION = new Matrix4f();
	private static final Matrix4f VP = new Matrix4f();
	private static boolean haveProjection;
	private static long vanillaFrames, vanillaSkipped;
	private static final boolean LOG = Boolean.getBoolean("cadence.probe") || System.getProperty("cadence.selftest") != null;
	private static ChunkSectionsToRender mainView, pyramidView;
	private static int frame = 1;
	private static long frames, logged, skipped;

	private HiZ() {
	}

	/** Cadence (WorldClosed): forget the last views. */
	public static void release() {
		mainView = null;
		pyramidView = null;
	}

	/** LevelRenderer.prepareChunkRendersIndirect RETURN, outside the shadow pass's preparation: this frame's camera view. */
	public static void mainPrepared(ChunkSectionsToRender view) {
		mainView = view;
	}

	/**
	 * The shadow pass's view. The shadow pass runs before this frame's camera view is prepared, and vanilla reuses its
	 * view objects, so last frame's camera view can come back as this frame's shadow view: forget it.
	 */
	public static void shadowPrepared(ChunkSectionsToRender view) {
		if (view == mainView) mainView = null;
		if (view == pyramidView) pyramidView = null;
	}

	public static void levelProjection(Matrix4f projection) {
		LEVEL_PROJECTION.set(projection);
		haveProjection = true;
	}

	/** Whether this renderGroup call is the camera's opaque terrain in the vanilla pass (no pack), to be culled. */
	public static boolean appliesVanilla(ChunkSectionsToRender view, ChunkSectionLayerGroup group) {
		return vanilla && enabled && group == ChunkSectionLayerGroup.OPAQUE && view == mainView && haveProjection && ShaderRuntime.get() == null
			&& GpuCull.available();
	}

	/**
	 * The vanilla pass's opaque terrain in two culled phases ({@code draw} = vanilla's renderGroup). Only when the open pass
	 * can be split and reopened exactly (else drawn as it is, every command once).
	 */
	public static void drawVanilla(ChunkSectionsToRender view, Runnable draw) {
		if (!GpuCull.canSplit()) {
			vanillaSkipped++;
			draw.run();
			return;
		}
		var access = (DrawIndirectAccess) view;
		Map<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> groups = access.cadence$groups();
		GpuBufferSlice infos = access.cadence$infos();
		var camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		var cam = camera.pos;
		int bx = (int) Math.floor(cam.x), by = (int) Math.floor(cam.y), bz = (int) Math.floor(cam.z);
		VP.set(LEVEL_PROJECTION).mul(camera.viewRotationMatrix);
		frame++;
		GpuCull.params(VP, bx, by, bz, (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz), frame, depthMode(LEVEL_PROJECTION));
		for (ChunkSectionLayer layer : ChunkSectionLayerGroup.OPAQUE.layers())
			for (var g : groups.getOrDefault(layer, List.of())) GpuCull.phaseAPre(g.indirectCommandBuffer(), g.drawCount(), infos);
		draw.run();
		long split = GpuCull.split();
		if (split == 0) {
			// can't happen after canSplit (nothing between changes the pass); stop rather than risk a frame missing terrain
			vanilla = false;
			LoggerFactory.getLogger("cadence").warn("Hi-Z (no pack) off: the terrain pass could not be split after phase 1");
			return;
		}
		for (ChunkSectionLayer layer : ChunkSectionLayerGroup.OPAQUE.layers())
			for (var g : groups.getOrDefault(layer, List.of())) GpuCull.phaseBOn(split, g.indirectCommandBuffer(), g.drawCount(), infos);
		GpuCull.resume(split);
		draw.run();
		vanillaFrames++;
		if (LOG && System.nanoTime() - logged > 30_000_000_000L) {
			logged = System.nanoTime();
			LoggerFactory.getLogger("cadence").info("Hi-Z occlusion (no pack): {} frames culled, {} frames the pass couldn't be split, depth mode {}",
				vanillaFrames, vanillaSkipped, depthMode(LEVEL_PROJECTION));
		}
	}

	/** GpuCull depth mode of a projection: clip depth 0..1 from the device, reversed when nearer points get larger depth. */
	private static int depthMode(Matrix4f p) {
		boolean z01 = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		float near = ndcZ(p, 0.051f), far = ndcZ(p, 512f);
		return (near > far ? GpuCull.REVERSED : 0) | (z01 ? GpuCull.ZERO_TO_ONE : 0);
	}

	private static float ndcZ(Matrix4f p, float distance) {
		float z = -distance;
		float cz = p.m22() * z + p.m32(), cw = p.m23() * z + p.m33();
		return cz / cw;
	}

	/** Whether this renderGroup call is the camera's opaque terrain in a pack pass, culled. */
	public static boolean applies(ChunkSectionsToRender view, ChunkSectionLayerGroup group, Object pass) {
		if (group == ChunkSectionLayerGroup.TRANSLUCENT)
			return enabled && view == mainView && pyramidView == view && pass instanceof ShaderRenderPass && GpuCull.available();
		if (!enabled || group != ChunkSectionLayerGroup.OPAQUE) return false;
		String why = view != mainView ? "not the camera view" : !(pass instanceof ShaderRenderPass) ? "not a pack pass"
			: !ShaderRuntime.isRendering() ? "pack not rendering" : ShaderRuntime.get().isShadow() ? "shadow pass"
			: !GpuCull.available() ? "kernels unavailable" : null;
		if (why != null) {
			declined.merge(why, 1L, Long::sum);
			if (LOG && System.nanoTime() - loggedDecline > 30_000_000_000L) {
				loggedDecline = System.nanoTime();
				LoggerFactory.getLogger("cadence").info("Hi-Z declined opaque terrain draws: {}", declined);
			}
		}
		return why == null;
	}

	private static final java.util.Map<String, Long> declined = new java.util.TreeMap<>();
	private static long loggedDecline;

	/** The camera's translucent terrain, after the opaque phases: sections behind this frame's pyramid are skipped. */
	public static void drawTranslucent(ChunkSectionsToRender view, ShaderRenderPass pass, Runnable draw) {
		var access = (DrawIndirectAccess) view;
		pass.suspend();
		for (ChunkSectionLayer layer : ChunkSectionLayerGroup.TRANSLUCENT.layers())
			for (var g : access.cadence$groups().getOrDefault(layer, List.of()))
				GpuCull.translucent(g.indirectCommandBuffer(), g.drawCount(), access.cadence$infos());
		draw.run();
	}

	/** Draws the camera's opaque terrain in two culled phases ({@code draw} = vanilla's renderGroup). */
	public static void draw(ChunkSectionsToRender view, ShaderRenderPass pass, Runnable draw) {
		var access = (DrawIndirectAccess) view;
		Map<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> groups = access.cadence$groups();
		GpuBufferSlice infos = access.cadence$infos();
		ShaderRuntime runtime = ShaderRuntime.get();
		var cam = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		int bx = (int) Math.floor(cam.x), by = (int) Math.floor(cam.y), bz = (int) Math.floor(cam.z);
		Matrix4f vp = new Matrix4f(runtime.uniforms().projection()).mul(runtime.uniforms().modelView());
		frame++;
		GpuCull.params(vp, bx, by, bz, (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz), frame);
		pass.suspend();
		int commands = 0;
		for (ChunkSectionLayer layer : ChunkSectionLayerGroup.OPAQUE.layers())
			for (var g : groups.getOrDefault(layer, List.of())) {
				GpuCull.phaseA(g.indirectCommandBuffer(), g.drawCount(), infos);
				commands += g.drawCount();
			}
		draw.run();
		// the terrain pipeline is bound now (at renderGroup's start the pass still had the previous one)
		var depth = pass.depthTexture();
		pass.suspend();
		if (depth == null) {
			// no depth to test against (shouldn't happen with a pack): draw what phase 1 skipped, and stop culling
			skipped++;
			for (ChunkSectionLayer layer : ChunkSectionLayerGroup.OPAQUE.layers())
				for (var g : groups.getOrDefault(layer, List.of())) GpuCull.rest(g.indirectCommandBuffer(), g.drawCount());
			draw.run();
			enabled = false;
			LoggerFactory.getLogger("cadence").info("Hi-Z occlusion off: the pack's terrain pass has no depth target");
			return;
		}
		GpuCull.pyramid(depth);
		pyramidView = view;
		for (ChunkSectionLayer layer : ChunkSectionLayerGroup.OPAQUE.layers())
			for (var g : groups.getOrDefault(layer, List.of())) GpuCull.phaseB(g.indirectCommandBuffer(), g.drawCount(), infos);
		draw.run();
		frames++;
		if (LOG && System.nanoTime() - logged > 30_000_000_000L) {
			logged = System.nanoTime();
			LoggerFactory.getLogger("cadence").info("Hi-Z occlusion: {} frames culled, {} commands this frame, {} frames without a depth target",
				frames, commands, skipped);
		}
	}
}
