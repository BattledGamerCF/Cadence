package dev.cadence.shaders.cadence.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Cadence: mixins of features that are off unless asked for at launch are not applied at all. Parallel world generation
 * (measured slower, off by default) needs its thread-safety changes (a concurrent structure palette cache, synchronized
 * structure placement) only when it runs: -Dcadence.parallelWorldgen=true applies all three.
 */
public final class CadenceMixinPlugin implements IMixinConfigPlugin {
	private static final Set<String> PARALLEL_WORLDGEN = Set.of("ChunkMapParallelMixin", "StructurePaletteCacheMixin", "StructureStartPlaceMixin");

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (PARALLEL_WORLDGEN.contains(name)) return Boolean.getBoolean("cadence.parallelWorldgen");
		return true;
	}

	@Override
	public void onLoad(String mixinPackage) {
		dev.cadence.metal.system.Legacy.migrateProperties();
	}

	@Override
	public String getRefMapperConfig() {
		return null;
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
