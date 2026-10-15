// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.edge;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Edge cap mixins (-Dcadence.edgeCap=true|N, see EdgeCap): applied only with the flag; without it the game is unchanged. */
public final class EdgeMixinPlugin implements IMixinConfigPlugin {
	@Override public void onLoad(String mixinPackage) {
		dev.cadence.metal.Profile.apply(); // before any flag is read: a profile may set cadence.edgeCap
	}
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String v = System.getProperty("cadence.edgeCap", "");
		return !v.isEmpty() && !"false".equals(v);
	}
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
