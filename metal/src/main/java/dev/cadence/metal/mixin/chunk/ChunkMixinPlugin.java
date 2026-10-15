// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.chunk;

import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.cadence.metal.chunk.ChunkOpt;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Each chunk mixin applies only when its -Dcadence.chunk.* switch is set (ChunkOpt.mixinEnabled), so by default nothing changes.
 * Mixins that rewrite methods other optimization mods also rewrite step aside when such a mod is loaded (that mod's version of
 * the optimization then applies): Lithium (POI scans, random ticks, chunk access), ScalableLux (the light engine), C2ME (chunk
 * IO, serialization, chunk access).
 */
public final class ChunkMixinPlugin implements IMixinConfigPlugin {
	static {
		dev.cadence.metal.Profile.apply(); // before any flag is read
	}
	private static final Map<String, List<String>> YIELDS_TO = Map.of(
		"AcquirePoiMixin", List.of("lithium"),
		"ServerLevelTickMixin", List.of("lithium"),
		"ChunkAccessSectionMixin", List.of("lithium", "c2me"),
		"DataLayerStorageMapMixin", List.of("scalablelux"),
		"BlockLightMapMixin", List.of("scalablelux"),
		"SkyLightMapMixin", List.of("scalablelux"),
		"PalettedContainerFactoryMixin", List.of("c2me"));

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (!ChunkOpt.mixinEnabled(name)) return false;
		for (String mod : YIELDS_TO.getOrDefault(name, List.of())) {
			if (FabricLoader.getInstance().isModLoaded(mod)) {
				System.out.println("cadence-chunk: " + name + " not applied: " + mod + " is loaded");
				return false;
			}
		}
		return true;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
