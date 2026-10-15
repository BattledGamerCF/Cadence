// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.cadence.metal.own.OwnMaterials;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Native shading on our near terrain (OwnMaterials.ON), section compiles only: the G-buffer wants ambient occlusion apart from the
 * albedo (the lighting applies it to the ambient term, not to direct sun). Vanilla's lighter leaves each corner's occlusion as a
 * grey colour, which the tint then multiplies; here the grey is taken out before the tint (the corners start white, so they get
 * the tint exactly) and put back as the corners' alpha, which terrain vertices otherwise keep at 255. gbuffer.metal reads it so.
 */
@Mixin(ModelBlockRenderer.class)
abstract class OwnIntAoMixin {
	@Shadow
	@Final
	private QuadInstance quadInstance;
	@Unique
	private final int[] cadence$ao = new int[4];
	@Unique
	private boolean cadence$split;

	@Inject(method = "putQuadWithTint", at = @At("HEAD"))
	private void cadence$aoOut(CallbackInfo ci) {
		this.cadence$split = OwnMaterials.ON && OwnMaterials.compiling();
		if (!this.cadence$split) return;
		for (int v = 0; v < 4; v++) {
			int c = this.quadInstance.getColor(v);
			this.cadence$ao[v] = c >>> 8 & 0xFF;  // the grey's green channel (r = g = b)
			this.quadInstance.setColor(v, -1);
		}
	}

	@Inject(method = "putQuadWithTint", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/block/BlockQuadOutput;put(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"))
	private void cadence$aoIn(CallbackInfo ci) {
		if (!this.cadence$split) return;
		for (int v = 0; v < 4; v++) this.quadInstance.setColor(v, this.quadInstance.getColor(v) & 0x00FFFFFF | this.cadence$ao[v] << 24);
	}
}
