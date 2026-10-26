package dev.cadence.metal.mixin.audio;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The Apple sound engine's hooks (dev.cadence.metal.audio.AppleAudio): applied unless -Dcadence.audio=openal (set by the user,
 * or by CompatLayers for a mod that hooks the game's audio classes); idle while the engine isn't running.
 */
public final class AudioMixinPlugin implements IMixinConfigPlugin {
	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return !"openal".equalsIgnoreCase(System.getProperty("cadence.audio", "apple"));
	}
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
