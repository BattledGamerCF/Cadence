package dev.cadence.metal.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Cadence compatibility layers (first version): other mods next to Cadence.
 *
 * <p>Cadence replaces some of Minecraft's code with faster versions (an @Overwrite of how entity models are written, its own
 * terrain renderer, ...). A mod that hooks the same method would lose its hook, or remove Cadence's. At launch (Profile.apply,
 * before any feature flag is read) this reads every other mod's mixins the way Mixin itself will: their configs from the mod's
 * fabric.mod.json, then each mixin class's @Mixin targets and the methods its injectors and overwrites name (Minecraft 26 is
 * not obfuscated, so the names match ours without remapping). Cadence's own mixins are read the same way. Where the two touch
 * the same method and either side replaces it (@Overwrite, @Redirect, @WrapMethod, a cancellable @Inject, ...), Cadence steps
 * aside for that mod: a "compatibility layer" is that area running on the vanilla code path, which the other mod was written
 * for, while every other Cadence optimization stays on.
 * <ul>
 * <li>Independent hooks (the CPU and game-logic mixins): only the conflicting mixin is left out ({@link #yields}).</li>
 * <li>Cadence's own terrain renderer (its mixins work together): turned off as a whole (cadence.own=false); terrain then goes
 * through the game's section pipeline, still drawn by the Metal backend with Cadence's culling.</li>
 * <li>Everything else: reported (log and the settings screen), not changed yet.</li>
 * </ul>
 * Two plain @Inject hooks on one method coexist (Mixin applies both) and are not a conflict. Fabric API and Cadence's own
 * modules are known to work together and are not scanned. -Dcadence.compat=false (or cadence.compat=false in
 * config/cadence.properties): nothing steps aside (only the report). -Dcadence.compat.log=true: every foreign target read.
 */
public final class CompatLayers {
	/** Injector annotations that replace or reshape vanilla code (a second mod on the same method may break). */
	private static final Set<String> REPLACING = Set.of(
		"Lorg/spongepowered/asm/mixin/Overwrite;",
		"Lorg/spongepowered/asm/mixin/injection/Redirect;",
		"Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
	/** Every injector annotation whose "method" names target methods. */
	private static final Set<String> INJECTORS = Set.of(
		"Lorg/spongepowered/asm/mixin/injection/Inject;",
		"Lorg/spongepowered/asm/mixin/injection/Redirect;",
		"Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
		"Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
		"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
		"Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
		"Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
		"Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
		"Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
		"Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
		"Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
		"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
		"Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

	/** One hook: a mixin's injector or overwrite on a target method. */
	/** Mod id to the name players know it by (fabric.mod.json "name"), for the report. */
	private static final Map<String, String> NAMES = new HashMap<>();

	record Hook(String mod, String mixin, String target, String method, boolean replacing) {
	}

	/** What stepped aside, and for whom. */
	public record Layer(String cadenceMixin, String area, Set<String> mods, boolean applied) {
	}

	private static final Map<String, Layer> LAYERS = new LinkedHashMap<>();
	private static final Set<String> YIELDING = new HashSet<>();
	private static boolean ran, ownOff;
	private static String summary = "";

	private CompatLayers() {
	}

	/** True when this Cadence mixin steps aside for another mod (its plugin then leaves it out). */
	public static synchronized boolean yields(String mixinClassName) {
		return YIELDING.contains(mixinClassName);
	}

	/** One line for the settings screen ("" when no other mod touches what Cadence replaces). */
	public static synchronized String summary() {
		return summary;
	}

	public static synchronized List<Layer> layers() {
		return List.copyOf(LAYERS.values());
	}

	/** Profile.apply, before any flag is read. Never throws: a failure leaves Cadence as it was, with a log line. */
	public static synchronized void apply() {
		if (ran) return;
		ran = true;
		try {
			boolean act = !"false".equalsIgnoreCase(System.getProperty("cadence.compat", "true"));
			List<Hook> ours = new ArrayList<>(), theirs = new ArrayList<>();
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				String id = mod.getMetadata().getId();
				NAMES.put(id, mod.getMetadata().getName());
				if (id.startsWith("cadence")) read(mod, ours);
				else if (!known(id)) read(mod, theirs);
			}
			if (Boolean.getBoolean("cadence.compat.log")) {
				for (Hook h : theirs) System.out.println("[cadence] compat: " + h);
			}
			System.out.println("[cadence] compat: read " + ours.size() + " Cadence hooks and " + theirs.size() + " from other mods");
			Map<String, Set<Hook>> byMethod = new HashMap<>();
			for (Hook h : theirs) byMethod.computeIfAbsent(h.target + "." + h.method, k -> new LinkedHashSet<>()).add(h);
			for (Hook o : ours) {
				Set<Hook> other = byMethod.get(o.target + "." + o.method);
				if (other == null) continue;
				Set<String> mods = new LinkedHashSet<>();
				for (Hook t : other) if (o.replacing || t.replacing) mods.add(NAMES.getOrDefault(t.mod, t.mod));
				if (mods.isEmpty()) continue;
				String area = area(o.mixin);
				boolean applied = act && (area.equals("own terrain renderer") || o.mixin.startsWith("dev.cadence.metal.mixin.cpu.")
					|| o.mixin.startsWith("dev.cadence.metal.mixin.logic."));
				Layer prev = LAYERS.get(o.mixin);
				if (prev != null) mods.addAll(prev.mods);
				LAYERS.put(o.mixin, new Layer(o.mixin, area, mods, applied));
				if (applied) {
					if (area.equals("own terrain renderer")) ownOff = true;
					else YIELDING.add(o.mixin);
				}
			}
			if (ownOff && !"false".equals(System.getProperty("cadence.own"))) System.setProperty("cadence.own", "false");
			report();
		} catch (RuntimeException | LinkageError e) {
			System.out.println("[cadence] compat: couldn't read the other mods' mixins (" + e + "); nothing changed");
		}
	}

	/** Mods known to work with Cadence as it is (Fabric API, the loader, Java, Minecraft itself). */
	private static boolean known(String id) {
		return id.equals("minecraft") || id.equals("java") || id.equals("fabricloader") || id.startsWith("fabric-") || id.equals("fabric-api")
			|| id.equals("mixinextras");
	}

	private static String area(String mixin) {
		String n = mixin.substring(mixin.lastIndexOf('.') + 1);
		if (mixin.contains(".mixin.own.") || mixin.contains(".mixin.ownmesh.")) return "own terrain renderer";
		if (n.startsWith("Model")) return "entity models";
		if (mixin.contains(".mixin.logic.")) return "game logic (" + n.replace("Mixin", "") + ")";
		if (mixin.contains(".mixin.cpu.")) return "render CPU (" + n.replace("Mixin", "") + ")";
		return n.replace("Mixin", "");
	}

	private static void report() {
		if (LAYERS.isEmpty()) {
			summary = "";
			System.out.println("[cadence] compat: no other mod touches what Cadence replaces");
			return;
		}
		Map<String, Set<String>> byArea = new LinkedHashMap<>();
		int active = 0;
		for (Layer l : LAYERS.values()) {
			byArea.computeIfAbsent(l.area + (l.applied ? "" : " (report only)"), k -> new LinkedHashSet<>()).addAll(l.mods);
			if (l.applied) active++;
		}
		StringBuilder b = new StringBuilder();
		for (var e : byArea.entrySet()) {
			if (b.length() > 0) b.append("; ");
			b.append(e.getKey()).append(" for ").append(String.join(", ", e.getValue()));
		}
		Set<String> mods = new LinkedHashSet<>();
		for (Layer l : LAYERS.values()) mods.addAll(l.mods);
		// the settings line stays short; the log has every area
		summary = active == 0 ? "Compatibility: other mods share code with Cadence (" + String.join(", ", mods) + "), nothing changed"
			: String.format(Locale.ROOT, "Compatibility: %d area%s use%s vanilla code for %s", active, active == 1 ? "" : "s",
				active == 1 ? "s" : "", String.join(", ", mods));
		System.out.println("[cadence] compat: " + summary + ": " + b);
	}

	/** Every mixin of a mod's configs (fabric.mod.json "mixins") as hooks. A mod may have several roots (a dev build: classes and
	 * resources apart), so each file is looked up in all of them. */
	private static void read(ModContainer mod, List<Hook> out) {
		String id = mod.getMetadata().getId();
		List<Path> roots = mod.getRootPaths();
		Path fmj = find(roots, "fabric.mod.json");
		if (fmj == null) return;
		for (String config : mixinConfigs(fmj)) {
			Path cfg = find(roots, config);
			if (cfg == null) continue;
			try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
				JsonObject c = JsonParser.parseReader(r).getAsJsonObject();
				String pkg = c.has("package") ? c.get("package").getAsString() : "";
				for (String side : new String[] {"mixins", "client", "server"}) {
					if (!c.has(side) || !c.get(side).isJsonArray()) continue;
					for (JsonElement m : c.getAsJsonArray(side)) {
						String cls = pkg.isEmpty() ? m.getAsString() : pkg + "." + m.getAsString();
						String name = cls.replace('.', '/') + ".class";
						Path file = find(roots, name);
						byte[] bytes = file != null ? Files.readAllBytes(file) : resource(name);
						if (bytes != null) hooks(id, cls, bytes, out);
					}
				}
			} catch (IOException | RuntimeException e) {
				System.out.println("[cadence] compat: skipped " + id + " " + config + ": " + e);
			}
		}
	}

	/** A class file from the class path (a dev launch keeps a mod's classes outside its root, beside its resources). */
	private static byte[] resource(String name) {
		try (var in = CompatLayers.class.getClassLoader().getResourceAsStream(name)) {
			return in == null ? null : in.readAllBytes();
		} catch (IOException e) {
			return null;
		}
	}

	private static Path find(List<Path> roots, String name) {
		for (Path root : roots) {
			Path p = root.resolve(name);
			if (Files.isRegularFile(p)) return p;
		}
		return null;
	}

	private static List<String> mixinConfigs(Path fmj) {
		List<String> out = new ArrayList<>();
		try (Reader r = Files.newBufferedReader(fmj, StandardCharsets.UTF_8)) {
			JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
			if (!o.has("mixins")) return out;
			for (JsonElement e : o.getAsJsonArray("mixins")) {
				if (e.isJsonPrimitive()) out.add(e.getAsString());
				else if (e.isJsonObject() && e.getAsJsonObject().has("config")) out.add(e.getAsJsonObject().get("config").getAsString());
			}
		} catch (IOException | RuntimeException e) {
			// not a readable fabric.mod.json: nothing to compare
		}
		return out;
	}

	/** A mixin class's hooks: each injector or overwrite, on each @Mixin target. */
	static void hooks(String mod, String mixin, byte[] bytes, List<Hook> out) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
		List<String> targets = targets(node);
		if (targets.isEmpty()) return;
		if (node.methods == null) return;
		for (MethodNode m : node.methods) {
			if (m.visibleAnnotations == null && m.invisibleAnnotations == null) continue;
			List<AnnotationNode> all = new ArrayList<>();
			if (m.visibleAnnotations != null) all.addAll(m.visibleAnnotations);
			if (m.invisibleAnnotations != null) all.addAll(m.invisibleAnnotations);
			for (AnnotationNode a : all) {
				if (a.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
					for (String t : targets) out.add(new Hook(mod, mixin, t, m.name, true));
				} else if (INJECTORS.contains(a.desc)) {
					boolean replacing = REPLACING.contains(a.desc) || a.desc.endsWith("/Inject;") && bool(a, "cancellable");
					for (String sel : strings(a, "method")) for (String t : targets) out.add(new Hook(mod, mixin, t, methodName(sel), replacing));
				}
			}
		}
	}

	/** @Mixin(value = {...}, targets = {...}) as binary class names with dots. */
	private static List<String> targets(ClassNode node) {
		List<String> out = new ArrayList<>();
		List<AnnotationNode> all = new ArrayList<>();
		if (node.visibleAnnotations != null) all.addAll(node.visibleAnnotations);
		if (node.invisibleAnnotations != null) all.addAll(node.invisibleAnnotations);
		for (AnnotationNode a : all) {
			if (!a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || a.values == null) continue;
			for (int i = 0; i < a.values.size(); i += 2) {
				String key = (String) a.values.get(i);
				Object v = a.values.get(i + 1);
				if (!(v instanceof List<?> list)) continue;
				for (Object e : list) {
					if (key.equals("value") && e instanceof Type t) out.add(t.getClassName());
					else if (key.equals("targets") && e instanceof String s) out.add(s.replace('/', '.'));
				}
			}
		}
		return out;
	}

	/** "name(desc)" / "name" / "Lowner;name(desc)" to the bare method name. */
	private static String methodName(String selector) {
		String s = selector;
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0) s = s.substring(semi + 1);
		int paren = s.indexOf('(');
		if (paren >= 0) s = s.substring(0, paren);
		int colon = s.indexOf(':');
		if (colon >= 0) s = s.substring(0, colon);
		return s;
	}

	private static List<String> strings(AnnotationNode a, String key) {
		List<String> out = new ArrayList<>();
		if (a.values == null) return out;
		for (int i = 0; i < a.values.size(); i += 2) {
			if (!key.equals(a.values.get(i))) continue;
			Object v = a.values.get(i + 1);
			if (v instanceof String s) out.add(s);
			else if (v instanceof List<?> list) for (Object e : list) if (e instanceof String s) out.add(s);
		}
		return out;
	}

	private static boolean bool(AnnotationNode a, String key) {
		if (a.values == null) return false;
		for (int i = 0; i < a.values.size(); i += 2) if (key.equals(a.values.get(i)) && Boolean.TRUE.equals(a.values.get(i + 1))) return true;
		return false;
	}
}
