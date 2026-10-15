package dev.cadence.shaders;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Cadence: the shader loader's and scene layer's game hooks apply when a shader pack is selected at launch, or when live
 * switching is on (the default: config key liveSwitching).
 *
 * <p>Several of them patch the same game code as Cadence's own near terrain (LevelRenderer's section draw lists, the section
 * compiler, the block renderer); two redirects of one call can't both apply. With a pack selected, Cadence's profile turns its
 * own terrain off (dev.cadence.metal.Profile.shaderPackSelected) and these apply; with none, this mod changes nothing at all and
 * Cadence runs at full speed. Selecting a pack already needs a restart, so nothing is lost. The same check as Cadence's:
 * -Dcadence.shaders.pack, else {@code pack=} in config/cadence-shaders.properties, non-blank.
 */
public final class CadencePackGate implements IMixinConfigPlugin {
	static {
		dev.cadence.metal.Legacy.migrateProperties(); // -DminecraftShaders.* / -DminecraftScene.* from before the rename, before any flag is read
	}
	private static final String PACK = selectedName();
	private static final boolean SELECTED = !PACK.isBlank();
	/** liveSwitching= in the config, default true: the hooks load even with no pack, so shaders can be turned on in game. */
	private static final boolean LIVE = !"false".equalsIgnoreCase(configValue("liveSwitching", "true").strip());
	private static final boolean HOOKS = SELECTED || LIVE;

	/** Whether this launch has the loader's hooks, so packs can be switched (on, off, another) without a restart. */
	public static boolean hooksApplied() {
		return HOOKS;
	}

	/** The live-switching setting this launch started with. */
	public static boolean liveSwitchingAtLaunch() {
		return LIVE;
	}

	private static String configValue(String key, String fallback) {
		Path cfg = dev.cadence.metal.Legacy.readable(FabricLoader.getInstance().getConfigDir(), dev.cadence.metal.Legacy.SHADERS_CONFIG, dev.cadence.metal.Legacy.SHADERS_CONFIG_OLD);
		if (!Files.isRegularFile(cfg)) return fallback;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
			p.load(r);
		} catch (IOException e) {
			return fallback;
		}
		return p.getProperty(key, fallback);
	}

	/** Whether a pack is selected for this launch (read once). */
	public static boolean selected() {
		return SELECTED;
	}

	/** The pack this launch started with, "" for none. */
	public static String selectedPack() {
		return PACK;
	}

	private static String selectedName() {
		String pack = System.getProperty("cadence.shaders.pack");
		if (pack == null) {
			Path cfg = dev.cadence.metal.Legacy.readable(FabricLoader.getInstance().getConfigDir(), dev.cadence.metal.Legacy.SHADERS_CONFIG, dev.cadence.metal.Legacy.SHADERS_CONFIG_OLD);
			if (!Files.isRegularFile(cfg)) return "";
			Properties p = new Properties();
			try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
				p.load(r);
			} catch (IOException e) {
				System.out.println("[cadence-shaders] can't read " + cfg + ": " + e);
				return "";
			}
			pack = p.getProperty("pack", "");
		}
		return pack.strip();
	}

	@Override
	public void onLoad(String mixinPackage) {
		if (mixinPackage.startsWith("dev.cadence.shaders"))
			System.out.println("[cadence-shaders] " + (SELECTED ? "shader pack selected: hooks on" : LIVE ? "live switching: hooks on, no pack yet"
				: "no shader pack, live switching off: hooks off, Cadence's renderer unchanged"));
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return HOOKS;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
