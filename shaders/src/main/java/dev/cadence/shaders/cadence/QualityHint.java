package dev.cadence.shaders.cadence;

import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Cadence: once per launch, when Auto resolution has nothing left to give (at its 50% floor for 30 s running, GPU-bound, frame
 * rate under 3/4 of the target), says in chat that the pack's lower Shader Quality preset is the remaining lever. Measured on
 * an M2 Air with BSL: LOW at 50% gave 51 fps where the default gave 35. Never changes a setting itself.
 */
final class QualityHint {
	private static int pinnedWindows;
	private static boolean shown;

	private QualityHint() {
	}

	/** How low a quality tier a profile name says it is (0 potato .. 3 medium), 99: not a quality tier. */
	static int tier(String profile) {
		String n = profile.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
		if (n.contains("potato") || n.contains("toaster")) return 0;
		if (n.contains("lowest") || n.contains("verylow") || n.contains("ultralow") || n.contains("minimal") || n.contains("lite")) return 1;
		if (n.equals("low") || n.startsWith("low") || n.endsWith("low") || n.contains("fast") || n.contains("performance")) return 2;
		if (n.equals("medium") || n.startsWith("med") || n.endsWith("medium")) return 3;
		return 99;
	}

	/**
	 * The presets to name, only ones whose name says they are a lower tier than the current one (Bliss's profiles are tonemaps:
	 * "SHADER_VERSION_LABEL" was offered): [gentlest step down, lowest or null when the same]. Pegasus's Medium costs 60% of its
	 * default and looks nearly the same; its lowest (Potato_Shadows) changes the look. Null: none.
	 */
	static String[] suggestions(List<String> profiles, String current) {
		int now = tier(current);
		String gentlest = null, lowest = null;
		for (String p : profiles) {
			int t = tier(p);
			if (t >= now || p.equals(current)) continue;
			if (gentlest == null || t > tier(gentlest)) gentlest = p;
			if (lowest == null || t < tier(lowest)) lowest = p;
		}
		if (gentlest == null) return null;
		return new String[] {gentlest, tier(lowest) < tier(gentlest) ? lowest : null};
	}

	/** One Auto window (RenderScale.frame, render thread). */
	static void window(float scale, double intervalMs, double gpuMs, double targetMs) {
		if (shown) return;
		boolean pinned = scale <= RenderScale.autoMin() + 0.001f && intervalMs > targetMs / 0.75 && gpuMs > targetMs * 0.85;
		pinnedWindows = pinned ? pinnedWindows + 1 : 0;
		if (pinnedWindows < 30) return;
		shown = true;
		String pack = ShaderSwitcher.active();
		if (pack.isEmpty()) return;
		List<String> profiles = ShaderToggle.profiles(pack);
		String current = ShaderToggle.profileFor(pack);
		String[] pick = suggestions(profiles, current);
		if (pick == null) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mc.player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
			"Cadence: shaders are limited by the GPU (about %.0f fps, already at 50%% resolution). "
				+ "Video Settings > Shaders > Quality: %s", 1000.0 / intervalMs,
			pick[1] == null ? pick[0] + " is much faster." : pick[0] + " is faster, " + pick[1] + " fastest."))
			.withStyle(ChatFormatting.GRAY));
	}
}
