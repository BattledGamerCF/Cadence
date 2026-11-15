package dev.cadence.shaders.cadence.compat;

import dev.cadence.shaders.cadence.setup.CadenceConfirmScreen;
import dev.cadence.shaders.cadence.ui.ShaderToggle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;

/**
 * Mods that can't run next to Cadence, found at launch and named at the title screen (every launch while they're installed),
 * so a broken picture or a failing shader pack isn't blamed on the wrong mod. Lithium gets a one-time notice instead: it works,
 * Cadence Logic just steps aside for it.
 */
public final class ModConflicts {
	public record Conflict(String id, String name, String why) {
	}

	private static final String RENDERER = "it also replaces Minecraft's renderer. Only one renderer can run at a time.";
	/** By mod id. (Sodium is refused by Fabric already: Cadence's fabric.mod.json breaks it.) */
	private static final Map<String, String> KNOWN = Map.ofEntries(
		Map.entry("metallum", RENDERER),
		Map.entry("metallum-mc", RENDERER),
		Map.entry("metallum_mc", RENDERER),
		Map.entry("metalrender", RENDERER),
		Map.entry("metal_render", RENDERER),
		Map.entry("metalmod", RENDERER),
		Map.entry("metalreplace", RENDERER),
		Map.entry("vulkanmod", RENDERER),
		Map.entry("optifabric", "OptiFine replaces the same rendering code as Cadence."),
		Map.entry("nvidium", "it needs an NVIDIA graphics card and OpenGL."));

	private static List<Conflict> found;

	private ModConflicts() {
	}

	public static synchronized List<Conflict> find() {
		if (found != null) return found;
		List<Conflict> out = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			var meta = mod.getMetadata();
			String id = meta.getId();
			if (id.startsWith("cadence")) continue;
			String why = KNOWN.get(id);
			if (why == null && looksLikeRenderer(id, meta.getName(), meta.getDescription())) why = RENDERER;
			if (why != null) out.add(new Conflict(id, meta.getName(), why));
		}
		for (Conflict c : out) System.out.println("[cadence] incompatible mod: " + c.name() + " (" + c.id() + "): " + c.why());
		return found = List.copyOf(out);
	}

	/** Another Metal (or Vulkan) renderer we don't know by id yet: named like one and described as one. */
	static boolean looksLikeRenderer(String id, String name, String description) {
		String n = (id + " " + name).toLowerCase(Locale.ROOT), d = description == null ? "" : description.toLowerCase(Locale.ROOT);
		boolean api = n.contains("metal") || n.contains("vulkan");
		boolean renderer = d.contains("renderer") || d.contains("rendering backend") || d.contains("render backend") || d.contains("backend")
			|| d.contains("opengl") || d.contains("graphics api") || d.contains("rendering api");
		return api && renderer && !n.contains("metallurgy") && !n.contains("heavy metal");
	}

	/**
	 * The title screen's first visit: a screen naming the incompatible mods (null when there are none), whose Continue returns
	 * to {@code parent}. Logged only during self-tests.
	 */
	public static Screen screen(Screen parent) {
		List<Conflict> list = find();
		if (list.isEmpty() || System.getProperty("cadence.selftest") != null) return null;
		return make(parent, list);
	}

	/** The screen itself (the self-test opens it with screen=conflicts). */
	public static Screen make(Screen parent, List<Conflict> list) {
		// (each sentence translated on its own: the text as one string matched no translation)
		StringBuilder text = new StringBuilder();
		for (Conflict c : list) text.append(c.name()).append(": ").append(dev.cadence.metal.system.Lang.tr(c.why())).append('\n');
		Component remove = list.size() == 1 ? dev.cadence.metal.system.Lang.literal("Remove it from your mods folder, or remove Cadence, then restart.")
			: dev.cadence.metal.system.Lang.literal("Remove them from your mods folder, or remove Cadence, then restart.");
		Component until = dev.cadence.metal.system.Lang.literal("Until then, the picture, shader packs and performance may be broken.");
		text.append('\n').append(remove.getString()).append(' ').append(until.getString());
		return new CadenceConfirmScreen(cont -> {
			if (cont) Minecraft.getInstance().gui.setScreen(parent);
			else Minecraft.getInstance().stop();
		}, dev.cadence.metal.system.Lang.literal(list.size() == 1 ? "A mod can't run with Cadence" : list.size() + " mods can't run with Cadence"),
			net.minecraft.network.chat.Component.literal(text.toString()),
			dev.cadence.metal.system.Lang.literal("Continue Anyway"), dev.cadence.metal.system.Lang.literal("Quit Game"), 360);
	}

	/** Lithium installed: said once (saved), never again. */
	public static void lithiumNotice() {
		if (!FabricLoader.getInstance().isModLoaded("lithium") || System.getProperty("cadence.selftest") != null) return;
		if ("true".equals(ShaderToggle.configValue("lithiumNoticeShown", "false").strip())) return;
		ShaderToggle.saveKey("lithiumNoticeShown", "true");
		Minecraft mc = Minecraft.getInstance();
		SystemToast.addOrUpdate(mc.gui.toastManager(), new SystemToast.SystemToastId(10000L),
			dev.cadence.metal.system.Lang.literal("You can keep Lithium"),
			dev.cadence.metal.system.Lang.literal("Cadence already does its job (Cadence Logic), so Cadence's version steps aside while Lithium is installed."));
	}

	/** Dynamic FPS installed: said every launch. It takes the frame-rate limit away from Battery Saver and Frame Generation. */
	public static void dynamicFpsNotice() {
		if (!FabricLoader.getInstance().isModLoaded("dynamic_fps") || System.getProperty("cadence.selftest") != null) return;
		Minecraft mc = Minecraft.getInstance();
		SystemToast.addOrUpdate(mc.gui.toastManager(), new SystemToast.SystemToastId(10001L),
			dev.cadence.metal.system.Lang.literal("Remove Dynamic FPS"),
			dev.cadence.metal.system.Lang.literal("Cadence already does what it does, and it overrides Battery Saver and Frame Generation."));
	}
}
