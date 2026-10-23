package dev.cadence.shaders.cadence.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.cadence.metal.MetalBridge;
import dev.cadence.metal.lod.LodPackFrame;
import dev.cadence.shaders.compile.MinecraftVertexAdapter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Cadence: the far terrain (Video Settings > Far Terrain) drawn by a shader pack, as Iris draws the Distant Horizons mod's:
 * through the pack's dh_terrain program, into the pack's colour buffers with its own depth (dhDepthTex0, then dhDepthTex1 for
 * the translucent phase), at the start of the level's solid pass (after the sky, before the real terrain, which then draws
 * over it). The pack sees Iris's DISTANT_HORIZONS macros and uniforms (dhProjection, dhFarPlane, ...), so its deferred and
 * composite passes fog and light the far terrain as they do under Distant Horizons.
 *
 * <p>The quads are the far terrain's own culled survivors (dev.cadence.metal.lod.LodPackFrame), read by the program's vertex
 * stage straight from the far terrain's buffers (MinecraftVertexAdapter.distantTerrain): no vertex buffers, no copies.
 * A pack without a dh_terrain program shows no far terrain (as under Iris with a pack that doesn't support DH).
 */
public final class DistantTerrain {
	private DistantTerrain() {
	}

	/** Far terrain is running in this launch (it is wired in at startup or not at all). */
	public static boolean available() {
		return LodPackFrame.available();
	}

	/** The far terrain's reach in blocks while it draws in this level, else 0. */
	public static double reachBlocks() {
		return available() ? LodPackFrame.reach() : 0;
	}

	/** The macros Iris defines while Distant Horizons runs: DISTANT_HORIZONS and the DH_BLOCK_* materials (EDhApiBlockMaterial). */
	public static Map<String, String> definitions() {
		Map<String, String> d = new LinkedHashMap<>();
		d.put("DISTANT_HORIZONS", "");
		String[] materials = {"UNKNOWN", "LEAVES", "STONE", "WOOD", "METAL", "DIRT", "LAVA", "DEEPSLATE", "SNOW", "SAND", "TERRACOTTA",
			"NETHER_STONE", "WATER", "GRASS", "AIR", "ILLUMINATED"};
		for (int i = 0; i < materials.length; i++) d.put("DH_BLOCK_" + materials[i], Integer.toString(i));
		return d;
	}

	/** Tells the far terrain whether this frame's pack draws it (false: no pack, or one without dh_terrain). */
	public static void want(boolean on) {
		if (available()) LodPackFrame.want(on);
	}

	/** Start of the solid pass: this frame's far terrain, culled for the pack. Whether there is anything to draw. */
	public static boolean prepare() {
		if (!available()) return false;
		LodPackFrame.prepare();
		return LodPackFrame.ready();
	}

	/** The native buffer behind one of the dh_terrain program's far-terrain buffers (MinecraftVertexAdapter.FAR_*), or 0. */
	public static long bufferHandle(int index) {
		if (!available() || !LodPackFrame.ready()) return 0;
		return index == MinecraftVertexAdapter.FAR_QUADS ? LodPackFrame.records() : index == MinecraftVertexAdapter.FAR_ARGS ? LodPackFrame.args() : 0;
	}

	/** Whether a set-1 buffer index is one of the far terrain's (bound by bufferHandle, not by the pack's buffer objects). */
	public static boolean isFarBuffer(int index) {
		return index == MinecraftVertexAdapter.FAR_QUADS || index == MinecraftVertexAdapter.FAR_ARGS;
	}

	/**
	 * The game pipeline a dh_terrain program is compiled against: no vertex inputs, back faces culled, depth tested and
	 * written (the game's reversed-depth convention; PackPipeline turns it into the pack's).
	 */
	public static RenderPipeline pipeline() {
		return RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("cadence", "pipeline/far_terrain"))
			.withVertexShader(Identifier.fromNamespaceAndPath("cadence", "far_terrain"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("cadence", "far_terrain"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(true)
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
			.build();
	}

	private static @Nullable GpuBuffer indices, args;
	private static long argsHandle;

	/** The quads' index buffer: 64 quads of {0, 1, 2, 2, 3, 0} + 4q (an instance of the draw), made once. */
	public static GpuBuffer indices() {
		if (indices == null) {
			ByteBuffer b = ByteBuffer.allocateDirect(64 * 6 * 2).order(ByteOrder.nativeOrder());
			for (int q = 0; q < 64; q++)
				for (int c : new int[] {0, 1, 2, 2, 3, 0}) b.putShort((short) (q * 4 + c));
			b.flip();
			indices = RenderSystem.getDevice().createBuffer(() -> "Cadence far terrain indices", GpuBuffer.USAGE_INDEX, b);
		}
		return indices;
	}

	/** This frame's draw arguments as a game buffer (a view of the far terrain's own), or null when there is nothing to draw. */
	public static @Nullable GpuBuffer drawArguments() {
		long h = LodPackFrame.ready() ? LodPackFrame.args() : 0;
		if (h == 0) return null;
		if (h != argsHandle || args == null) {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) return null;
			// (the old view: the far terrain released its memory with its world; the view owns nothing)
			args = MetalBridge.foreignBuffer(encoder, h, GpuBuffer.USAGE_INDIRECT_PARAMETERS, 512);
			argsHandle = h;
		}
		return args;
	}
}
