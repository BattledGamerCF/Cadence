// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.ownmesh;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Our own mesher's mixins apply only with -Dcadence.own=true, without Sodium, and with -Dcadence.own.mesh=true|verify
 * or -Dcadence.own.mesh.stats=true. Otherwise the game is byte-for-byte what it is without them.
 */
public final class OwnMeshMixinPlugin implements IMixinConfigPlugin {
	static {
		dev.cadence.metal.system.Profile.apply(); // before any flag is read
	}

	private static final String MODE = System.getProperty("cadence.own.mesh", "false");
	private static final boolean MESH = "true".equals(MODE) || "verify".equals(MODE);
	private static final boolean ENABLED = Boolean.getBoolean("cadence.own") && !FabricLoader.getInstance().isModLoaded("sodium")
		&& (MESH || Boolean.getBoolean("cadence.own.mesh.stats"));

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!ENABLED) return false;
		// the holder mixins only carry our quads: needed only when our mesher feeds the arena
		boolean region = Boolean.getBoolean("cadence.own.mesh.region");
		if (mixinClassName.endsWith("LighterCacheMixin")) return region;
		boolean frapiRegion = region && Boolean.getBoolean("cadence.own.mesh.frapiRegion");
		if (mixinClassName.endsWith("RegionStateMixin")) return frapiRegion;
		if (mixinClassName.endsWith("RegionAccessMixin") || mixinClassName.endsWith("SectionCopyMixin")) return region && (Boolean.getBoolean("cadence.own.mesh.prefill") || frapiRegion);
		if (mixinClassName.endsWith("TintMixin")) return region && Boolean.getBoolean("cadence.own.mesh.tint");
		return mixinClassName.endsWith("CompilerMixin") || "true".equals(MODE);
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
