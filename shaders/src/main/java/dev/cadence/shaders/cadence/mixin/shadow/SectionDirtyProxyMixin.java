package dev.cadence.shaders.cadence.mixin.shadow;

import dev.cadence.shaders.cadence.shadow.ShadowProxies;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (ShadowProxies): a changed section's column proxy (and its neighbours' border walls) is rebuilt. */
@Mixin(LevelExtractor.class)
public abstract class SectionDirtyProxyMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void cadence$proxyDirty(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
		ShadowProxies.sectionDirty(x, z);
	}
}
