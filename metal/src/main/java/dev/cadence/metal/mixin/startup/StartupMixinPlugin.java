// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.startup;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Each startup mixin applies only when its -Dcadence.startup.* flag asks for it; with none set the game is unchanged. */
public final class StartupMixinPlugin implements IMixinConfigPlugin {
	static {
		dev.cadence.metal.Profile.apply(); // before any flag is read
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		// System properties only: loading dev.cadence.metal.Startup here would load game classes before their own mixins apply.
		if (mixinClassName.endsWith("NarratorMixin")) return "lazy".equals(System.getProperty("cadence.startup.narrator"));
		if (mixinClassName.endsWith("CrashPreloadMixin")) return "async".equals(System.getProperty("cadence.startup.crashPreload"));
		if (mixinClassName.endsWith("BlockCacheMixin") || mixinClassName.endsWith("BootstrapCacheMixin")) return !System.getProperty("cadence.startup.blockCache", "").isEmpty();
		if (mixinClassName.endsWith("FadeMixin")) return System.getProperty("cadence.startup.fadeMs") != null;
		return false;
	}
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
