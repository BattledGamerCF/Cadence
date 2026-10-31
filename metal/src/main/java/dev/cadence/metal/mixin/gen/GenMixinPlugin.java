// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.gen;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The generation experiments' mixins apply only with their flags: -Dcadence.gen.worldgenQos (GenWorldgenQosMixin),
 * -Dcadence.gen.nativeNoise (GenNoiseChunkMixin).
 * Without them the game is byte for byte what it is without these mixins. Reads the properties itself, so nothing of
 * dev.cadence.metal.lod loads before the game does.
 */
public final class GenMixinPlugin implements IMixinConfigPlugin {
	static {
		dev.cadence.metal.Profile.apply(); // before any flag is read
	}

	private static final boolean WORLDGEN_QOS = System.getProperty("cadence.gen.worldgenQos") != null;
	private static final boolean NATIVE_NOISE = !System.getProperty("cadence.gen.nativeNoise", "").isBlank();

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	/**
	 * World generation's density-buffer pools keep a buffer this many clean-ups instead of vanilla's 20 (GenBufferPoolMixin): on by
	 * default (1,000,000: kept); -Dcadence.gen.bufferAge=20 is vanilla. Public: read from inside RandomState.
	 */
	public static final int BUFFER_AGE = Integer.getInteger("cadence.gen.bufferAge", 1_000_000);

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".GenWorldgenQosMixin")) return WORLDGEN_QOS;
		if (mixinClassName.endsWith(".GenNoiseChunkMixin")) return NATIVE_NOISE;
		if (mixinClassName.endsWith(".GenBufferPoolMixin")) return BUFFER_AGE > 0 && BUFFER_AGE != 20;
		if (mixinClassName.endsWith(".GenSurfaceScopeMixin") || mixinClassName.endsWith(".GenSurfaceBufferMixin"))
			return Boolean.getBoolean("cadence.gen.surfaceBuffers") && !dev.cadence.metal.compat.CompatLayers.yields(mixinClassName);
		return false;
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
