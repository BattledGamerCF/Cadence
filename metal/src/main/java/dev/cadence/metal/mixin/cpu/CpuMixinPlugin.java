// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.cpu;

import java.util.List;
import java.util.Set;
import dev.cadence.metal.cpu.Cpu;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** These mixins apply only when a -Dcadence.cpu.* property turns their lever on, so by default the game is unchanged. */
public final class CpuMixinPlugin implements IMixinConfigPlugin {
	static {
		dev.cadence.metal.Profile.apply(); // before any flag is read
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (dev.cadence.metal.compat.CompatLayers.yields(mixinClassName)) return false;  // (another mod hooks the same code)
		if (name.startsWith("CullFast")) return false;  // (the culler mixins need Sodium: not in this tree)
		if (name.endsWith("Access")) return false;  // (likewise)
		if (name.startsWith("Cull")) return Cpu.cullHooks();
		if (name.startsWith("AbFrame")) return Cpu.AB_SHOTS || Cpu.PASS_AB || Cpu.LEASH_AB || Cpu.LISTS_AB || Cpu.MODEL_AB || Cpu.MERGE_AB || dev.cadence.metal.cpu.EntityBox.AB;
		if (name.startsWith("List")) return Cpu.LISTS;
		if (name.startsWith("Leash")) return Cpu.LEASH;
		if (name.startsWith("DrawMerge")) return Cpu.MERGE_ON;
		if (name.startsWith("Model")) return Cpu.MODEL;
		if (name.startsWith("ParticleQuad")) return Cpu.MODEL;  // (with the model writes: it uses their BufferBuilder accessor)
		if (name.startsWith("EntityBox") || name.startsWith("EntityRendererBox")) return dev.cadence.metal.cpu.EntityBox.ON;
		if (name.startsWith("Loading")) return Cpu.LOADING_FPS > 0;
		return false;
	}
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
