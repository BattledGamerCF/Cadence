// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.chunkio;

import java.util.List;
import java.util.Set;
import dev.cadence.metal.chunkio.ChunkIo;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * saveSkip's mixin (RegionFileStorageMixin) applies only with -Dcadence.chunk.saveSkip=true|probe, so by default nothing changes.
 * It steps aside when C2ME is loaded (C2ME writes region files through its own storage thread, never through the hooked call).
 * Vanilla and Fabric classes only: a build without Sodium carries this config as is.
 */
public final class ChunkIoMixinPlugin implements IMixinConfigPlugin {
	static {
		// the integrated builds apply their default profile (e.g. -Dcadence.chunk.saveSkip) before any flag is read
		try {
			Class.forName("dev.cadence.metal.Profile").getMethod("apply").invoke(null);
		} catch (ClassNotFoundException e) {
			// a build without profiles: the flags come from the command line only
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("cadence: profile could not be applied", e);
		}
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (!ChunkIo.mixinEnabled(name)) return false;
		if (FabricLoader.getInstance().isModLoaded("c2me")) {
			System.out.println("cadence-chunk: " + name + " not applied: c2me is loaded");
			return false;
		}
		return true;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
