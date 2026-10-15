// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.own.AnimCopy;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dcadence.own.int.atlasWrite: marks the animated atlases' texture creation (blocks, gui, particles) so it gets shader-write usage. */
@Mixin(TextureAtlas.class)
public class OwnAtlasWriteMixin {
	@Inject(method = "createTexture", at = @At("HEAD"))
	private void cadence$atlasWriteHead(int width, int height, int mipLevel, CallbackInfo ci) {
		AnimCopy.creatingAtlas((TextureAtlas) (Object) this, true);
	}

	@Inject(method = "createTexture", at = @At("RETURN"))
	private void cadence$atlasWriteReturn(int width, int height, int mipLevel, CallbackInfo ci) {
		AnimCopy.creatingAtlas((TextureAtlas) (Object) this, false);
	}
}
