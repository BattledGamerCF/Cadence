package dev.cadence.metal.mixin.logic;

import dev.cadence.metal.logic.Logic;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Cadence Logic (see dev.cadence.metal.logic.Logic): each mixin applies when the set is on (cadence.logic=true, set by the
 * performance profiles) and its own switch isn't off; all of them step aside when Lithium is installed.
 */
public final class LogicMixinPlugin implements IMixinConfigPlugin {
	static {
		dev.cadence.metal.Profile.apply(); // before any flag is read
	}
	/** Mixin class -> its switch name (cadence.logic.NAME). */
	private static final Map<String, String> OPTION = Map.ofEntries(
		Map.entry("EntitySectionStorageMixin", "entitySections"),
		Map.entry("BrainMixin", "brain"),
		Map.entry("SecondaryPoiSensorMixin", "villagerSensor"),
		Map.entry("LevelBlockEntityTickMixin", "blockEntityTick"),
		Map.entry("TickRangeTrackerMixin", "tickRange"),
		Map.entry("TickRangeChunkTicksMixin", "tickRange"),
		Map.entry("TickRangeLevelMixin", "tickRange"),
		Map.entry("TickRangeTicksMixin", "tickRange"),
		Map.entry("PathTypeCacheMixin", "pathCache"),
		Map.entry("CollisionAirMixin", "collisionAir"));

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		String option = OPTION.get(name);
		if (option == null || !Logic.enabled(option)) return false;
		if (dev.cadence.metal.compat.CompatLayers.yields(mixinClassName)) return false;  // (another mod hooks the same code)
		if (FabricLoader.getInstance().isModLoaded("lithium")) {
			System.out.println("cadence-logic: " + option + " not applied: Lithium is loaded");
			return false;
		}
		return true;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
