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
final class NativeShaders {
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
		RenderPipeline pipeline = match(info);
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
		return log(info, new Source(header + common + "\n" + body, "native " + file));
	}

	private static Source log(CreateInfo info, Source s) {
		if (LOG) System.out.println("[cadence] native shaders: " + info.name() + ": " + s.why());
		return s;
	}

	/**
	 * The game's pipeline this CreateInfo was made from. Names aren't unique (pipeline/item_cutout is the plain, the glint and
	 * the special-glint item pipeline, each with its own defines), so among the pipelines of that name the one whose uniforms,
	 * vertex attributes and fixed-function state are the CreateInfo's; null unless exactly one shader source fits (two that
	 * fit with the same shaders and defines are the same program).
	 */
	private static @Nullable RenderPipeline match(CreateInfo info) {
		List<RenderPipeline> named = pipelines().get(info.name());
		if (named == null) return null;
		RenderPipeline found = null;
		for (RenderPipeline p : named) {
			if (!fits(p, info)) continue;
			if (found != null && !(found.getShaders().equals(p.getShaders()) && found.getShaderDefines().equals(p.getShaderDefines())))
				return null;
			found = p;
		}
		return found;
	}

	private static boolean fits(RenderPipeline p, CreateInfo info) {
		var uniforms = com.mojang.renderpearl.api.pipeline.BindGroupLayout.flattenUniforms(p.getBindGroupLayouts());
		if (uniforms.size() != info.uniforms().size()) return false;
		for (int i = 0; i < uniforms.size(); i++) if (!uniforms.get(i).name().equals(info.uniforms().get(i).name())) return false;
		int attributes = 0;
		for (var format : p.getVertexFormatBindings()) attributes += format.getElements().size();
		return attributes == info.attribBindings().size() && p.isCull() == info.cull() && p.getPrimitiveTopology() == info.primitiveTopology()
			&& p.getPolygonMode() == info.polygonMode() && java.util.Objects.equals(p.getDepthStencilState(), info.depthStencilState())
			&& p.getColorTargetStates().equals(info.colorTargetStates()) && p.pushConstantSize() == info.pushConstantsSize();
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
