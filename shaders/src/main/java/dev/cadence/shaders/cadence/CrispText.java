package dev.cadence.shaders.cadence;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;

/**
 * Cadence: text in the world (name tags above players, server holograms, text displays) at full resolution while World
 * Resolution draws the world smaller. Drawn with the world, a name was a few scaled-down pixels high, stretched back up and
 * sharpened: on servers like Hypixel the lines above players and NPCs were a smear. Now the text draws are held back from the
 * scaled world pass (WorldTextDeferMixin) and drawn after the upscale into the full-size image, against the world's depth
 * scaled up (so text behind walls stays hidden); the hand is drawn after, at full resolution, in front of it. -Dcadence.crispText=false: off.
 * Render thread.
 */
public final class CrispText {
	public static volatile boolean ON = !"false".equals(System.getProperty("cadence.crispText"));

	private static final List<Consumer<RenderPass>> deferred = new ArrayList<>();
	private static GpuBufferSlice projection, fog;
	private static ProjectionType projectionType;
	private static CompiledRenderPipeline depthPipeline;
	private static GpuFormat depthPipelineFormat;
	private static GpuSampler nearest;

	private CrispText() {
	}

	/** WorldTextDeferMixin: whether a text group is held back now (World Resolution scaling this frame, no shader pack). */
	public static boolean deferring() {
		return ON && WorldScale.active();
	}

	/** A text group held back, to draw into a pass given later; the world's projection and fog are kept for it. */
	public static void defer(Consumer<RenderPass> draw) {
		if (deferred.isEmpty()) {
			projection = RenderSystem.getProjectionMatrixBuffer();
			projectionType = RenderSystem.getProjectionType();
			fog = RenderSystem.getShaderFog();
		}
		deferred.add(draw);
	}

	/**
	 * WorldScale.end, after the upscale: the world's depth (and the hand's, as nearest) scaled up into the main target's depth,
	 * then the held-back text drawn into the main target.
	 */
	static void drawDeferred(GpuTextureView worldDepth, GpuTextureView mainColor, GpuTextureView mainDepth) {
		if (deferred.isEmpty()) return;
		GpuDevice device = RenderSystem.getDevice();
		try {
			upscaleDepth(device, worldDepth, mainColor, mainDepth);
			GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer(), savedFog = RenderSystem.getShaderFog();
			ProjectionType savedType = RenderSystem.getProjectionType();
			RenderSystem.setProjectionMatrix(projection, projectionType);
			if (fog != null) RenderSystem.setShaderFog(fog);
			var descriptor = RenderPassDescriptor.builder(() -> "Cadence crisp world text").withColorAttachment(mainColor)
				.withDepthAttachment(mainDepth).build();
			try (RenderPass pass = device.createCommandEncoder().createRenderPass(descriptor)) {
				for (Consumer<RenderPass> draw : deferred) draw.accept(pass);
			} finally {
				if (savedProjection != null) RenderSystem.setProjectionMatrix(savedProjection, savedType);
				if (savedFog != null) RenderSystem.setShaderFog(savedFog);
			}
		} finally {
			deferred.clear();
			projection = fog = null;
		}
	}

	/** A frame that held text back but never reached the upscale (the world closed mid-frame): forgotten. */
	static void drop() {
		deferred.clear();
		projection = fog = null;
	}

	private static final String VERTEX = """
		#version 450
		layout(location=0) out vec2 uv;
		void main() {
		  uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
		  gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
		}
		""";
	// the nearest texel of the scaled depth (the hand is drawn after the text, with its own depth)
	private static final String FRAGMENT = """
		#version 450
		layout(location=0) in vec2 uv;
		layout(location=0) out vec4 color;
		uniform sampler2D WorldDepth;
		void main() {
		  ivec2 size = textureSize(WorldDepth, 0);
		  ivec2 p = clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
		  gl_FragDepth = texelFetch(WorldDepth, p, 0).r;
		  color = vec4(0.0);
		}
		""";

	private static void upscaleDepth(GpuDevice device, GpuTextureView worldDepth, GpuTextureView mainColor,
		GpuTextureView mainDepth) {
		GpuFormat color = mainColor.texture().getFormat(), depth = mainDepth.texture().getFormat();
		if (depthPipeline == null || depthPipelineFormat != color) {
			var description = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("cadence_shaders", "crisp_text_depth/" + color.name().toLowerCase(java.util.Locale.ROOT)))
				.withVertexShader(Identifier.fromNamespaceAndPath("cadence_shaders", "crisp_text_depth"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("cadence_shaders", "crisp_text_depth"))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withColorTargetState(new ColorTargetState(Optional.empty(), color, ColorTargetState.WRITE_NONE))
				.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
				.withBindGroupLayout(BindGroupLayout.builder().withUniform("WorldDepth", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withCull(false)
				.build();
			ShaderSource source = new ShaderSource() {
				public String getShader(Identifier id, ShaderType type) {
					return type == ShaderType.VERTEX ? VERTEX : FRAGMENT;
				}

				public CachedIncludeSource getInclude(Identifier id) {
					return null;
				}

				public void close() {
				}
			};
			depthPipeline = device.compilePipeline(description, source, Runnable::run).join().finishCompile();
			depthPipelineFormat = color;
			if (nearest == null)
				nearest = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1,
					OptionalDouble.of(0));
		}
		var descriptor = RenderPassDescriptor.builder(() -> "Cadence crisp text depth").withColorAttachment(mainColor)
			.withDepthAttachment(mainDepth).build();
		try (RenderPass pass = device.createCommandEncoder().createRenderPass(descriptor)) {
			pass.setPipeline(depthPipeline);
			pass.setUniform("WorldDepth", worldDepth, nearest);
			pass.draw(3, 1, 0, 0);
		}
	}
}
