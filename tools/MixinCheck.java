// Cadence: checks every compiled mixin against the Minecraft jar without starting the game.
//
// Mixin only resolves its targets when the game loads a class, so a misspelled method, a wrong descriptor or a call site
// that isn't in the method body shows up as a crash at launch. This tool reads each mixin's annotations with ASM (the
// library Mixin uses) and checks, the way Mixin matches them:
//   - @Mixin targets exist;
//   - injector `method` selectors (@Inject, @Redirect, @WrapOperation, @ModifyExpressionValue, ...) name a method of a target;
//   - @At(INVOKE/INVOKE_ASSIGN) and @At(FIELD) targets appear in that method's bytecode with the same owner, name and
//     descriptor, and @At(NEW) constructs the named class there;
//   - @Overwrite, @Shadow, @Accessor and @Invoker members exist in the target or its supertypes.
// Targets in classes that aren't on the classpath (other mods' compat mixins) are reported as skipped, not as errors.
//
// usage: java -cp <asm jars> tools/MixinCheck.java <classpath of jars/dirs to resolve against> <mixin class dirs...>
// (tools/mixin-check.sh builds the classpaths.)
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class MixinCheck {
	static final Map<String, Path> dirIndex = new HashMap<>();
	static final Map<String, JarFile> jarIndex = new HashMap<>();
	static final Map<String, ClassNode> cache = new HashMap<>();
	static int errors, checked, skipped;

	public static void main(String[] args) throws Exception {
		for (String entry : args[0].split(":")) index(Path.of(entry));
		List<Path> mixinDirs = new ArrayList<>();
		for (int i = 1; i < args.length; i++) {
			mixinDirs.add(Path.of(args[i]));
			index(Path.of(args[i]));
		}
		for (Path dir : mixinDirs) {
			try (var walk = Files.walk(dir)) {
				for (Path p : walk.filter(f -> f.toString().endsWith(".class")).sorted().toList()) checkClass(read(Files.readAllBytes(p)));
			}
		}
		System.out.printf("mixin check: %d members checked, %d skipped (target not on classpath), %d errors%n", checked, skipped, errors);
		System.exit(errors == 0 ? 0 : 1);
	}

	static void index(Path entry) throws IOException {
		if (Files.isDirectory(entry)) {
			try (var walk = Files.walk(entry)) {
				walk.filter(f -> f.toString().endsWith(".class")).forEach(f -> {
					String name = entry.relativize(f).toString();
					dirIndex.putIfAbsent(name.substring(0, name.length() - 6), f);
				});
			}
		} else if (entry.toString().endsWith(".jar") && Files.isRegularFile(entry)) {
			JarFile jar = new JarFile(entry.toFile());
			for (var e = jar.entries(); e.hasMoreElements();) {
				String name = e.nextElement().getName();
				if (name.endsWith(".class")) jarIndex.putIfAbsent(name.substring(0, name.length() - 6), jar);
			}
		}
	}

	static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		return node;
	}

	static ClassNode load(String internalName) {
		if (cache.containsKey(internalName)) return cache.get(internalName);
		ClassNode node = null;
		try {
			Path p = dirIndex.get(internalName);
			if (p != null) node = read(Files.readAllBytes(p));
			else {
				JarFile jar = jarIndex.get(internalName);
				if (jar != null) try (InputStream in = jar.getInputStream(jar.getJarEntry(internalName + ".class"))) {
					node = read(in.readAllBytes());
				}
			}
		} catch (IOException e) {
			node = null;
		}
		cache.put(internalName, node);
		return node;
	}

	static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
		if (list != null) for (AnnotationNode a : list) if (a.desc.equals(desc)) return a;
		return null;
	}

	static Object value(AnnotationNode a, String key) {
		if (a == null || a.values == null) return null;
		for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(key)) return a.values.get(i + 1);
		return null;
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Object v) {
		if (v == null) return List.of();
		return v instanceof List ? (List<Object>) v : List.of(v);
	}

	static List<AnnotationNode> all(List<AnnotationNode> a, List<AnnotationNode> b) {
		List<AnnotationNode> out = new ArrayList<>();
		if (a != null) out.addAll(a);
		if (b != null) out.addAll(b);
		return out;
	}

	static void error(ClassNode mixin, String member, String message) {
		errors++;
		System.out.println("ERROR " + mixin.name.replace('/', '.') + (member == null ? "" : "." + member) + ": " + message);
	}

	static void checkClass(ClassNode mixin) {
		AnnotationNode mx = annotation(all(mixin.invisibleAnnotations, mixin.visibleAnnotations), "Lorg/spongepowered/asm/mixin/Mixin;");
		if (mx == null) return;
		List<ClassNode> targets = new ArrayList<>();
		boolean unresolved = false;
		for (Object t : list(value(mx, "value"))) {
			String name = ((Type) t).getInternalName();
			ClassNode n = load(name);
			if (n == null) unresolved = true;
			else targets.add(n);
		}
		for (Object t : list(value(mx, "targets"))) {
			String name = ((String) t).replace('.', '/');
			ClassNode n = load(name);
			if (n == null) unresolved = true;
			else targets.add(n);
		}
		if (targets.isEmpty()) {
			skipped++;
			if (!unresolved) error(mixin, null, "no @Mixin target");
			return;
		}
		String prefix = "shadow$";
		for (FieldNode f : mixin.fields) {
			List<AnnotationNode> anns = all(f.invisibleAnnotations, f.visibleAnnotations);
			if (annotation(anns, "Lorg/spongepowered/asm/mixin/Shadow;") != null) {
				checked++;
				if (targets.stream().noneMatch(t -> findField(t, f.name, f.desc)))
					error(mixin, f.name, "@Shadow field " + f.name + " " + f.desc + " not found in target");
			}
		}
		for (MethodNode m : mixin.methods) {
			List<AnnotationNode> anns = all(m.invisibleAnnotations, m.visibleAnnotations);
			for (AnnotationNode a : anns) {
				String kind = a.desc.substring(a.desc.lastIndexOf('/') + 1, a.desc.length() - 1);
				switch (kind) {
					case "Shadow" -> {
						checked++;
						String p = Objects.toString(value(a, "prefix"), prefix);
						String name = m.name.startsWith(p) ? m.name.substring(p.length()) : m.name;
						if (targets.stream().noneMatch(t -> findMethod(t, name, m.desc)))
							error(mixin, m.name, "@Shadow method " + name + m.desc + " not found in target");
					}
					case "Overwrite" -> {
						checked++;
						if (targets.stream().noneMatch(t -> own(t, m.name, m.desc)))
							error(mixin, m.name, "@Overwrite target " + m.name + m.desc + " not found");
					}
					case "Accessor" -> {
						checked++;
						String field = Objects.toString(value(a, "value"), "");
						if (field.isEmpty()) field = inferAccessor(m.name);
						String desc = m.desc.startsWith("()") ? Type.getReturnType(m.desc).getDescriptor() : Type.getArgumentTypes(m.desc)[0].getDescriptor();
						String f = field, d = desc;
						if (targets.stream().noneMatch(t -> findField(t, f, d)))
							error(mixin, m.name, "@Accessor field " + field + " " + desc + " not found");
					}
					case "Invoker" -> {
						checked++;
						String name = Objects.toString(value(a, "value"), "");
						if (name.isEmpty()) name = inferInvoker(m.name);
						String n = name;
						boolean ctor = n.equals("<init>");
						if (targets.stream().noneMatch(t -> ctor ? own(t, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getArgumentTypes(m.desc))) : findMethod(t, n, m.desc)))
							error(mixin, m.name, "@Invoker " + name + m.desc + " not found");
					}
					case "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant", "WrapOperation",
						"WrapWithCondition", "ModifyExpressionValue", "ModifyReturnValue", "ModifyReceiver", "WrapMethod" -> checkInjector(mixin, m, a, kind, targets);
					default -> {
					}
				}
			}
		}
	}

	static String inferAccessor(String method) {
		for (String p : List.of("get", "set", "is")) if (method.startsWith(p) && method.length() > p.length())
			return Character.toLowerCase(method.charAt(p.length())) + method.substring(p.length() + 1);
		return method;
	}

	static String inferInvoker(String method) {
		for (String p : List.of("call", "invoke", "new", "create")) if (method.startsWith(p) && method.length() > p.length()) {
			if (p.equals("new") || p.equals("create")) return "<init>";
			return Character.toLowerCase(method.charAt(p.length())) + method.substring(p.length() + 1);
		}
		return method;
	}

	static void checkInjector(ClassNode mixin, MethodNode handler, AnnotationNode a, String kind, List<ClassNode> targets) {
		List<Object> selectors = list(value(a, "method"));
		if (selectors.isEmpty()) {
			error(mixin, handler.name, "@" + kind + " has no method selector");
			return;
		}
		List<AnnotationNode> ats = new ArrayList<>();
		for (Object at : list(value(a, "at"))) ats.add((AnnotationNode) at);
		for (Object s : selectors) {
			checked++;
			String sel = (String) s;
			List<MethodNode> matched = new ArrayList<>();
			for (ClassNode t : targets) for (MethodNode tm : t.methods) if (matches(sel, tm)) matched.add(tm);
			if (matched.isEmpty()) {
				error(mixin, handler.name, "@" + kind + " method \"" + sel + "\" matches no method of " + names(targets));
				continue;
			}
			for (AnnotationNode at : ats) {
				String point = Objects.toString(value(at, "value"), "");
				String target = Objects.toString(value(at, "target"), "");
				if (target.isEmpty() && !point.equals("NEW")) continue;
				boolean found = false;
				for (MethodNode tm : matched) if (contains(tm, point, target, at)) found = true;
				if (!found && !target.isEmpty())
					error(mixin, handler.name, "@" + kind + " @At(" + point + ") target \"" + target + "\" not found in " + sel);
			}
		}
	}

	static String names(List<ClassNode> targets) {
		return targets.stream().map(t -> t.name.replace('/', '.')).toList().toString();
	}

	/** Mixin's selector forms: name, name(desc)ret, Lowner;name(desc)ret, name* wildcards. */
	static boolean matches(String sel, MethodNode m) {
		String s = sel;
		if (s.startsWith("L") && s.contains(";")) s = s.substring(s.indexOf(';') + 1);
		int paren = s.indexOf('(');
		String name = paren < 0 ? s : s.substring(0, paren);
		String desc = paren < 0 ? null : s.substring(paren);
		if (name.endsWith("*")) {
			if (!m.name.startsWith(name.substring(0, name.length() - 1))) return false;
		} else if (!name.isEmpty() && !name.equals(m.name)) return false;
		return desc == null || desc.equals(m.desc);
	}

	static boolean contains(MethodNode m, String point, String target, AnnotationNode at) {
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			switch (point) {
				case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> {
					if (insn instanceof MethodInsnNode mi && ("L" + mi.owner + ";" + mi.name + mi.desc).equals(target)) return true;
					if (insn instanceof InvokeDynamicInsnNode) continue;
				}
				case "FIELD" -> {
					if (insn instanceof FieldInsnNode fi && ("L" + fi.owner + ";" + fi.name + ":" + fi.desc).equals(target)) return true;
				}
				case "NEW" -> {
					// "Lowner;", "owner", or a constructor "(args)Lowner;"
					String t = target.startsWith("(") ? target.substring(target.indexOf(')') + 1) : target;
					String owner = t.startsWith("L") && t.endsWith(";") ? t.substring(1, t.length() - 1) : t.replace('.', '/');
					if (insn instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW && (owner.isEmpty() || ti.desc.equals(owner))) return true;
				}
				default -> {
					return true; // HEAD, RETURN, TAIL, CONSTANT, JUMP, LOAD, STORE...: not resolved here
				}
			}
		}
		return false;
	}

	static boolean own(ClassNode c, String name, String desc) {
		for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true;
		return false;
	}

	static boolean findMethod(ClassNode c, String name, String desc) {
		Deque<String> todo = new ArrayDeque<>(List.of(c.name));
		Set<String> seen = new HashSet<>();
		while (!todo.isEmpty()) {
			ClassNode n = load(todo.pop());
			if (n == null || !seen.add(n.name)) continue;
			if (own(n, name, desc)) return true;
			if (n.superName != null) todo.add(n.superName);
			todo.addAll(n.interfaces);
		}
		return false;
	}

	static boolean findField(ClassNode c, String name, String desc) {
		for (ClassNode n = c; n != null; n = n.superName == null ? null : load(n.superName))
			for (FieldNode f : n.fields) if (f.name.equals(name) && f.desc.equals(desc)) return true;
		return false;
	}
}
