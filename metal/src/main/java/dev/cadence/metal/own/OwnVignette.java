// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.util.Map;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Exact opt-in removal of a HUD vignette whose packed RGB is zero. No texture-dependent or rounded-positive-source skip. */
public final class OwnVignette {
	private static final boolean ON = Boolean.getBoolean("cadence.own.vignetteNoop");
	private static final boolean CHECK = ON || OwnProbe.ON;
	private static volatile RenderPipeline verifiedPipeline;
	private static final boolean AUDIT = Boolean.getBoolean("cadence.own.vignetteAudit");
	private static final Identifier SHADER = Identifier.withDefaultNamespace("core/position_tex_color");
	private static int lastColor;
	private static long calls, skips;
	private static boolean lastSkipped;
	private static boolean announced;

	private OwnVignette() { }

	/** Fail closed while shaders reload, including a failed reload retaining a previous pipeline. */
	public static void invalidateShaders() {
		verifiedPipeline = null;
	}

	/** The two vanilla shaders contain their uniform declarations inline: there are no unverified includes. */
	public static void shadersApplied() {
		if (!CHECK) return;
		RenderPipeline candidate = RenderPipelines.VIGNETTE;
		boolean vanillaShaders = candidate.getShaderDefines().isEmpty() && candidate.getShaders().size() == 2
			&& candidate.getShaders().values().stream().allMatch(SHADER::equals)
			&& !candidate.getVertexFormatBindings().isEmpty() && candidate.getVertexFormatBinding(0) == DefaultVertexFormat.POSITION_TEX_COLOR
			&& candidate.getVertexFormatBindings().stream().skip(1).allMatch(v -> v == null)
			&& matches("position_tex_color.vsh", "98df7a3e71c56e710e3957015c22501524f9cf6a8ce92bbca5f46b901e25ff6d")
			&& matches("position_tex_color.fsh", "598daa2518ddc018438107cd7e2eb0375b4c083f5c00152662b03c5e0ac8b7ff");
		verifiedPipeline = vanillaShaders ? candidate : null;
		System.out.println("cadence-own vignetteNoop: vanilla shader guard " + vanillaShaders);
	}

	private static boolean matches(String file, String expected) {
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(Identifier.withDefaultNamespace("shaders/core/" + file))) {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())).equals(expected);
		} catch (Exception e) {
			return false;
		}
	}

	public static Map<String, Object> audit() {
		return Map.of("calls", calls, "skips", skips, "lastColor", String.format("%08x", lastColor),
			"lastSkipped", lastSkipped, "shaderGuard", verifiedPipeline != null, "enabled", AUDIT);
	}

	public static boolean skip(RenderPipeline pipeline, int color) {
		if (AUDIT) { calls++; lastColor = color; lastSkipped = false; }
		if (!OwnProbe.bool("vignetteNoop", ON) || SharedConstants.DEBUG_RENDER_UI_LAYERING_RECTANGLES || (color & 0xffffff) != 0 || pipeline != verifiedPipeline || OwnTerrain.get() == null || pipeline.getDepthStencilState() != null || pipeline.getColorTargetStates().size() != 1) return false;
		var target = pipeline.getColorTargetStates().get(0);
		if (target == null || target.blendFunction().isEmpty()) return false;
		var blend = target.blendFunction().get();
		if (blend.color().sourceFactor() != BlendFactor.ZERO || blend.color().destFactor() != BlendFactor.ONE_MINUS_SRC_COLOR
			|| blend.color().op() != BlendOp.ADD || blend.alpha().sourceFactor() != BlendFactor.ZERO
			|| blend.alpha().destFactor() != BlendFactor.ONE || blend.alpha().op() != BlendOp.ADD) return false;
		// Every vertex RGB is zero. The verified shader multiplies bounded texture * vertex colour * finite vanilla modulation.
		// RGB source=0 exactly, destination factor=1 exactly; alpha factor=1. No depth/stencil writes. Discard also changes nothing.
		if (AUDIT) { skips++; lastSkipped = true; }
		if (!announced) {
			announced = true;
			System.out.println("cadence-own vignetteNoop: skipped exact black-source HUD draw");
		}
		return true;
	}
}
