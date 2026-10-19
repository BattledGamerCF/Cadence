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
 * hand-written Metal Shading Language instead of their GLSL run through SPIR-V and SPIRV-Cross. Each program is one file under
 * cadence/native/ (core/entity.metal: native_vs and native_fs) written from vanilla's GLSL, with the same #ifdef variants; it is
 * compiled with the pipeline's own defines (ShaderDefines.asSourceDirectives: the preprocessor reads GLSL's #define lines as
 * they are) and NATIVE_SLOT_<uniform> for each uniform's argument-table slot (MetalPipeline: uniform i in slot i). Vertex inputs
 * keep vanilla's layout locations.
 *
 * <p>Used only where it gives exactly what vanilla's shaders give: a pipeline of the game's own list (RenderPipelines) whose
 * vertex and fragment programs have a native file, without order-independent transparency variants (not written yet), and only
 * while no resource pack or mod replaces any shader file the program reads (its .vsh, .fsh and every #include, checked against
 * the resource stack at each compile). Anything else, and every shader pack pipeline, is translated as before.
 */
final class NativeShaders {
	static final boolean ON = Boolean.parseBoolean(System.getProperty("cadence.native",
		NativeShaders.class.getResource("/cadence/native/ENABLED") != null ? "true" : "false"));
	/** -Dcadence.native.log=true: which pipelines are native, and why the others aren't. */
	private static final boolean LOG = Boolean.getBoolean("cadence.native.log");

	private static final Pattern INCLUDE = Pattern.compile("#include\\s*<([a-z0-9_.-]+):([a-z0-9_./-]+)>");
	private static volatile Map<String, RenderPipeline> byName;
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
		RenderPipeline pipeline = pipelines().get(info.name());
		if (pipeline == null) return log(info, new Source(null, "not one of the game's pipelines"));
		Identifier vs = pipeline.getShaders().get(ShaderType.VERTEX), fs = pipeline.getShaders().get(ShaderType.FRAGMENT);
		if (vs == null || fs == null) return log(info, new Source(null, "no vertex or fragment program"));
		String defines = pipeline.getShaderDefines().asSourceDirectives();
		if (defines.contains("OIT")) return log(info, new Source(null, "order-independent transparency variant"));
		String file = vs.equals(fs) ? vs.getPath() : vs.getPath() + "+" + fs.getPath().substring(fs.getPath().lastIndexOf('/') + 1);
		String body = resource("/cadence/native/" + file + ".metal");
		if (body == null) return log(info, new Source(null, "no native " + file));
		String replaced = replacedBy(vs, fs);
		if (replaced != null) return log(info, new Source(null, "a shader file is replaced by " + replaced));
		StringBuilder header = new StringBuilder("// Cadence native: ").append(file).append(" for ").append(info.name()).append('\n');
		header.append(defines);
		var uniforms = info.uniforms();
		for (int i = 0; i < uniforms.size(); i++) header.append("#define NATIVE_SLOT_").append(uniforms.get(i).name()).append(' ').append(i).append('\n');
		return log(info, new Source(header + body, "native " + file));
	}

	private static Source log(CreateInfo info, Source s) {
		if (LOG) System.out.println("[cadence] native shaders: " + info.name() + ": " + s.why());
		return s;
	}

	/** The game's pipelines by name (RenderPipelines' required and optional lists). */
	private static Map<String, RenderPipeline> pipelines() {
		Map<String, RenderPipeline> m = byName;
		if (m != null) return m;
		m = new HashMap<>();
		for (RenderPipeline p : RenderPipelines.requiredPipelines()) m.put(p.getLocation().toString(), p);
		for (RenderPipeline p : RenderPipelines.optionalPipelines()) m.put(p.getLocation().toString(), p);
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
