// Cadence: offline regression checks for fixes that are otherwise only visible in game (no game started).
// Run with tools/offline-checks/run.sh after ./gradlew build.
import dev.cadence.shaders.ShaderLoaderConfig;
import dev.cadence.shaders.cadence.render.VanillaRgss;
import dev.cadence.shaders.pack.ShaderPack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;

public class OfflineChecks {
	static int failures;

	static void check(boolean ok, String what) {
		System.out.println((ok ? "OK    " : "FAIL  ") + what);
		if (!ok) failures++;
	}

	public static void main(String[] args) throws Exception {
		// translations (dev.cadence.metal.system.Lang): exact keys, trimmed edges, templates with parts translated in turn
		System.setProperty("cadence.lang", "de_de");
		String perf = dev.cadence.metal.system.Lang.tr("Performance: Recommended");
		check(!perf.equals("Performance: Recommended") && !perf.contains("{"), "Lang: a template and its part translated (" + perf + ")");
		check(dev.cadence.metal.system.Lang.tr("Max Quality").equals(dev.cadence.metal.system.Lang.tr("Max Quality:").replaceAll(":$", "")), "Lang: trailing punctuation kept around a translated key");
		check(dev.cadence.metal.system.Lang.tr("zzz untranslated zzz").equals("zzz untranslated zzz"), "Lang: text without an entry stays English");
		System.setProperty("cadence.lang", "en_gb");
		check(dev.cadence.metal.system.Lang.tr("Performance: Recommended").equals("Performance: Recommended"), "Lang: en_gb is the text as written");
		System.clearProperty("cadence.lang");
		Path mcJar = Path.of(args[0]), packs = Path.of(args[1]);

		// 1. the vanilla RGSS patch: applied to Minecraft's own include, exactly one early return, nothing else changed
		String include;
		try (ZipFile z = new ZipFile(mcJar.toFile())) {
			include = new String(z.getInputStream(z.getEntry("assets/minecraft/shaders/include/texture_sampling.glsl")).readAllBytes());
		}
		String patched = VanillaRgss.patch("minecraft:shaders/include/texture_sampling.glsl", include);
		check(!patched.equals(include) && patched.split("Cadence \\(VanillaRgss\\)", -1).length == 2, "vanilla RGSS patch applies once to Minecraft 26.3's include");
		check(patched.replace("    if (blendFactor == 0.0) return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize); // Cadence (VanillaRgss)\n", "").equals(include),
			"vanilla RGSS patch adds only the early return");
		check(VanillaRgss.patch("x/texture_sampling.glsl", "// a resource pack's own version\n").equals("// a resource pack's own version\n"),
			"vanilla RGSS patch leaves a resource pack's version alone");

		// 2. a profile the pack lacks is ignored (it refused MakeUp at launch); a real one still expands
		ShaderPack makeup = ShaderPack.load(packs.resolve("MakeUp-UltraFast-9.5g.zip"));
		check(makeup.profileOverrides("MINIMUM").isEmpty(), "MakeUp: unknown profile MINIMUM ignored, no exception");
		ShaderPack bsl = ShaderPack.load(packs.resolve("BSL_v10.1.8.zip"));
		check(!bsl.profileOverrides("LOW").isEmpty(), "BSL: profile LOW still expands");

		// 3. per-pack settings: shaderpacks/<pack>.txt (Iris's file) and profile.<pack>= are what a launch uses
		Path game = Files.createTempDirectory("cadence-offline");
		Path dir = game.resolve("shaderpacks"), cfg = game.resolve("config");
		Files.createDirectories(dir);
		Files.createDirectories(cfg);
		Files.copy(packs.resolve("BSL_v10.1.8.zip"), dir.resolve("BSL_v10.1.8.zip"));
		Files.writeString(dir.resolve("BSL_v10.1.8.zip.txt"), "#Iris-style\nSHADOW=false\n");
		Files.writeString(cfg.resolve("cadence-shaders.properties"), "pack=BSL_v10.1.8.zip\nprofile=MINIMUM\nprofile.BSL_v10.1.8.zip=LOW\n");
		ShaderLoaderConfig c = ShaderLoaderConfig.load(game, cfg);
		check("LOW".equals(c.profile()), "profile.<pack> wins over the shared profile");
		Map<String, String> eff = c.effectiveOptions(ShaderPack.load(c.pack()));
		check("false".equals(eff.get("SHADOW")), "the pack's .txt setting is applied (SHADOW=false)");
		Files.writeString(dir.resolve("BSL_v10.1.8.zip.txt"), "SHADOW=\\uZZZZ broken\n");
		boolean loaded;
		try {
			ShaderLoaderConfig.load(game, cfg);
			loaded = true;
		} catch (Exception e) {
			loaded = false;
		}
		check(loaded, "an unreadable .txt doesn't refuse the pack");

		System.out.println(failures == 0 ? "all offline checks passed" : failures + " offline check(s) FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
