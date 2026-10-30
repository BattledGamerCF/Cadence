package dev.cadence.metal.mixin.net;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The native network path (dev.cadence.metal.net.FastNet): applied unless -Dcadence.net=false, or another mod that replaces
 * the same network stages is installed (then that mod's are used).
 */
public final class NetMixinPlugin implements IMixinConfigPlugin {
	private static final List<String> OTHERS = List.of("krypton", "kryptonfnp", "pluto", "ceres", "raknetify", "zstdmc", "bandwidthoptimizer");
	private boolean apply;

	@Override
	public void onLoad(String mixinPackage) {
		apply = !"false".equalsIgnoreCase(System.getProperty("cadence.net", "true"));
		for (String id : OTHERS) {
			if (apply && FabricLoader.getInstance().isModLoaded(id)) {
				System.out.println("[cadence] Network: " + id + " is installed; Cadence's native network path is off");
				apply = false;
			}
		}
	}

	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return apply; }
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
