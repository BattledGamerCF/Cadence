package dev.cadence.metal;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline.CreateInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Cadence (the native track; -Dcadence.native=true, the default in the experimental build): vanilla's shader programs as
 * hand-written Metal Shading Language instead of their GLSL run through SPIR-V and SPIRV-Cross. Each program is common.metal
 * and one file under cadence/native/ (core/entity.metal: native_vs and native_fs) written from vanilla's GLSL, with the same
 * #ifdef variants; it is compiled with the pipeline's own defines (ShaderDefines.asSourceDirectives: the preprocessor reads
 * GLSL's #define lines as they are) and NATIVE_SLOT_<uniform> for each uniform's argument-table slot (MetalPipeline: uniform i
 * in slot i). Vertex inputs keep vanilla's layout locations.
 *
 * <p>Used only where it gives exactly what vanilla's shaders give: a pipeline of the game's own list (RenderPipelines) whose
 * vertex and fragment programs have a native file (for an order-independent transparency variant, a file that says "native:
 * oit"), and only while no resource pack or mod replaces any shader file the program reads (its .vsh, .fsh and every
 * #include, checked against the resource stack at each compile). Anything else, and every shader pack pipeline, is translated
 * as before.
 */
public final class NativeShaders {
	static final boolean ON = Boolean.parseBoolean(System.getProperty("cadence.native",
		NativeShaders.class.getResource("/cadence/native/ENABLED") != null ? "true" : "false"));
	/** -Dcadence.native.log=true: which pipelines are native, and why the others aren't. */
	private static final boolean LOG = Boolean.getBoolean("cadence.native.log");

	private static final Pattern INCLUDE = Pattern.compile("#include\\s*<([a-z0-9_.-]+):([a-z0-9_./-]+)>");
	private static volatile Map<String, List<RenderPipeline>> byName;
	private static final Map<String, String> SOURCES = new HashMap<>();

	private NativeShaders() {
	}

	record Source(String msl, String why) {
		boolean ok() {
			return this.msl != null;
		}
	}

	/** The native MSL for this pipeline (both stages: native_vs, native_fs), or null with the reason it isn't native. */
	static Source source(CreateInfo info) {
		if (!ON) return new Source(null, "off");
		try {
			return find(info);
		} catch (RuntimeException e) {
			// (whatever goes wrong here, the pipeline is translated as before: never a failed resource reload)
			return log(info, new Source(null, "lookup failed: " + e));
		}
	}

	/**
	 * NativePipelineSourceMixin: the pipeline a CreateInfo was made from, kept until that CreateInfo is compiled (weakly: one never
	 * compiled goes with it; keyed by the record's equality, and two equal CreateInfos are the same program anyway).
	 */
	public static void madeFrom(CreateInfo info, RenderPipeline pipeline) {
		if (ON && info != null) synchronized (MADE_FROM) {
			MADE_FROM.put(info, pipeline);
		}
	}

	private static final Map<CreateInfo, RenderPipeline> MADE_FROM = new java.util.WeakHashMap<>();
	/** Native sources Metal refused this session: translated straight away at the next reload or pack switch. */
	private static final Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** MetalPipeline: this native source didn't compile or make a pipeline. */
	static void failed(String msl) {
		FAILED.add(msl);
	}

	private static Source find(CreateInfo info) {
		RenderPipeline known;
		synchronized (MADE_FROM) {
			known = MADE_FROM.remove(info);
		}
		// (the game's own pipelines only: one of RenderPipelines' lists, as the name lookup below requires)
		List<RenderPipeline> named = pipelines().get(info.name());
		RenderPipeline pipeline = known != null ? (named != null && named.contains(known) ? known : null) : match(info);
		if (pipeline == null) return log(info, new Source(null, "not one of the game's pipelines (or not one alone)"));
		Identifier vs = pipeline.getShaders().get(ShaderType.VERTEX), fs = pipeline.getShaders().get(ShaderType.FRAGMENT);
		if (vs == null || fs == null) return log(info, new Source(null, "no vertex or fragment program"));
		String defines = pipeline.getShaderDefines().asSourceDirectives();
		String file = vs.equals(fs) ? vs.getPath() : vs.getPath() + "+" + fs.getPath().substring(fs.getPath().lastIndexOf('/') + 1);
		String body = resource("/cadence/native/" + file + ".metal");
		if (body == null) return log(info, new Source(null, "no native " + file));
		if (defines.contains("OIT") && !body.contains("native: oit")) return log(info, new Source(null, "order-independent transparency variant"));
		String common = resource("/cadence/native/common.metal");
		if (common == null) return log(info, new Source(null, "no common.metal"));
		String replaced = replacedBy(vs, fs);
		if (replaced != null) return log(info, new Source(null, "a shader file is replaced by " + replaced));
		StringBuilder header = new StringBuilder("// Cadence native: ").append(file).append(" for ").append(info.name()).append('\n');
		header.append(defines);
		// (Cadence's RGSS shortcut, as VanillaRgss patches vanilla's include; off with -Dcadence.vanillaRgss=false or =verify)
		if ("true".equals(System.getProperty("cadence.vanillaRgss", "true"))) header.append("#define NATIVE_RGSS_SKIP 1\n");
		var uniforms = info.uniforms();
		for (int i = 0; i < uniforms.size(); i++) header.append("#define NATIVE_SLOT_").append(uniforms.get(i).name()).append(' ').append(i).append('\n');
		// (the defines first: common.metal's order-independent transparency block reads them)
		String msl = header + common + "\n" + body;
		if (FAILED.contains(msl)) return log(info, new Source(null, "native " + file + " failed before"));
		return log(info, new Source(msl, "native " + file));
	}

	private static Source log(CreateInfo info, Source s) {
		if (LOG) System.out.println("[cadence] native shaders: " + info.name() + ": " + s.why());
		return s;
	}

	/**
	 * The game's pipeline this CreateInfo was made from. Names aren't unique (pipeline/item_cutout is the plain, the glint and
	 * the special-glint item pipeline, each with its own defines). The frontend hands the backend the pipeline's fixed-function
	 * state and vertex buffers as they are, but only the attributes and uniforms its compiled shaders use; so a candidate fits
	 * when its state and buffers are the CreateInfo's and its uniforms include the CreateInfo's, and the one declaring the
	 * fewest uniforms beyond them wins (the plain item pipeline over the glint one). null unless one program is left (two
	 * that tie with the same shaders and defines are the same program).
	 */
	private static @Nullable RenderPipeline match(CreateInfo info) {
		List<RenderPipeline> named = pipelines().get(info.name());
		if (named == null) return null;
		RenderPipeline found = null;
		int best = Integer.MAX_VALUE;
		boolean tied = false;
		for (RenderPipeline p : named) {
			int extra = extraUniforms(p, info);
			if (extra < 0 || extra > best) continue;
			if (extra < best) {
				best = extra;
				found = p;
				tied = false;
			} else if (!(found.getShaders().equals(p.getShaders()) && found.getShaderDefines().equals(p.getShaderDefines()))) {
				tied = true;
			}
		}
		return tied ? null : found;
	}

	/** How many uniforms p declares beyond those info's shaders use, or -1 when p can't be what info was made from. */
	private static int extraUniforms(RenderPipeline p, CreateInfo info) {
		if (p.isCull() != info.cull() || p.getPrimitiveTopology() != info.primitiveTopology() || p.getPolygonMode() != info.polygonMode()
			|| !java.util.Objects.equals(p.getDepthStencilState(), info.depthStencilState()) || !p.getColorTargetStates().equals(info.colorTargetStates())
			|| p.pushConstantSize() != info.pushConstantsSize()) return -1;
		var formats = p.getVertexFormatBindings();
		int buffers = 0;
		for (int slot = 0; slot < formats.size(); slot++) {
			var format = formats.get(slot);
			if (format == null) continue;  // (an unused binding slot)
			if (buffers >= info.vertexBuffers().size()) return -1;
			var vb = info.vertexBuffers().get(buffers++);
			if (vb.bufferSlot() != slot || vb.stride() != format.getVertexSize() || vb.stepRate() != format.getStepRate()) return -1;
		}
		if (buffers != info.vertexBuffers().size()) return -1;
		Set<String> declared = new HashSet<>();
		for (var u : com.mojang.renderpearl.api.pipeline.BindGroupLayout.flattenUniforms(p.getBindGroupLayouts())) declared.add(u.name());
		for (var u : info.uniforms()) if (!declared.contains(u.name())) return -1;
		return declared.size() - info.uniforms().size();
	}

	/** The game's pipelines by name (RenderPipelines' required and optional lists; a name can have several). */
	private static Map<String, List<RenderPipeline>> pipelines() {
		Map<String, List<RenderPipeline>> m = byName;
		if (m != null) return m;
		m = new HashMap<>();
		for (RenderPipeline p : RenderPipelines.requiredPipelines()) m.computeIfAbsent(p.getLocation().toString(), k -> new java.util.ArrayList<>()).add(p);
		for (RenderPipeline p : RenderPipelines.optionalPipelines()) m.computeIfAbsent(p.getLocation().toString(), k -> new java.util.ArrayList<>()).add(p);
		return byName = m;
	}

	/** The pack (not "vanilla") that provides any shader file these programs read, with their includes; null when none. */
	private static @Nullable String replacedBy(Identifier vs, Identifier fs) {
		var resources = Minecraft.getInstance().getResourceManager();
		Set<Identifier> files = new HashSet<>();
		List<Identifier> todo = new java.util.ArrayList<>(List.of(
			vs.withPath("shaders/" + vs.getPath() + ".vsh"), fs.withPath("shaders/" + fs.getPath() + ".fsh")));
		while (!todo.isEmpty()) {
			Identifier id = todo.removeLast();
			if (!files.add(id)) continue;
			var stack = resources.getResourceStack(id);
			if (stack.isEmpty()) return "nothing (" + id + " is missing)";
			var top = stack.getLast();
			if (!"vanilla".equals(top.sourcePackId())) return top.sourcePackId() + " (" + id + ")";
			try (InputStream in = top.open()) {
				Matcher m = INCLUDE.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
				while (m.find()) todo.add(Identifier.fromNamespaceAndPath(m.group(1), "shaders/include/" + m.group(2)));
			} catch (IOException e) {
				return "an unreadable file (" + id + ")";
			}
		}
		return null;
	}

	private static synchronized @Nullable String resource(String path) {
		if (SOURCES.containsKey(path)) return SOURCES.get(path);
		String text = null;
		try (InputStream in = NativeShaders.class.getResourceAsStream(path)) {
			if (in != null) text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			text = null;
		}
		SOURCES.put(path, text);
		return text;
	}
}
