// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.Lightmap;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dcadence.metal.lightmapDouble: the lightmap double-buffered. Vanilla rewrites its one 16x16 lightmap on the ticks that change it, and the
 * terrain samples it in the vertex stage; the rewrite has to wait until the previous frame (still on the GPU) is done reading it, and
 * this frame's main pass can't start its vertex work until the rewrite is done, so on those frames the main pass loses the overlap with
 * the previous frame (~0.6-0.9 ms of GPU on the base chips). Here each update draws into the texture that isn't the current one and then
 * makes it current: nothing in flight reads it. Exact: the update is a full-screen triangle without blending whose output depends only on
 * its uniforms, so every texel is rewritten, and getTextureView() hands out the one last written. 1 KB more memory.
 */
@Mixin(Lightmap.class)
public class LightmapDoubleMixin {
	@Unique
	private static final boolean MCOPT_ON = Boolean.getBoolean("cadence.metal.lightmapDouble");

	@Shadow
	@Final
	private GpuTextureView textureView;

	@Shadow
	@Final
	private static Vector4fc CLEAR_COLOR;

	@Unique
	private GpuTexture cadence$texture2;
	@Unique
	private GpuTextureView cadence$view2;
	@Unique
	private boolean cadence$secondCurrent, cadence$drew;

	@Redirect(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/Lightmap;textureView:Lcom/mojang/renderpearl/api/textures/GpuTextureView;"))
	private GpuTextureView cadence$target(Lightmap self) {
		if (!MCOPT_ON) return this.textureView;
		if (this.cadence$view2 == null) {
			GpuDevice device = RenderSystem.getDevice();
			this.cadence$texture2 = device.createTexture("Lightmap 2", 13, GpuFormat.RGBA8_UNORM, 16, 16, 1, 1);
			this.cadence$view2 = device.createTextureView(this.cadence$texture2);
			device.createCommandEncoder().clearColorTexture(this.cadence$texture2, CLEAR_COLOR);
		}
		this.cadence$drew = true;
		return this.cadence$secondCurrent ? this.textureView : this.cadence$view2;  // the one not current
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void cadence$swap(CallbackInfo ci) {
		if (this.cadence$drew) this.cadence$secondCurrent = !this.cadence$secondCurrent;
		this.cadence$drew = false;
	}

	@Inject(method = "getTextureView", at = @At("HEAD"), cancellable = true)
	private void cadence$current(CallbackInfoReturnable<GpuTextureView> cir) {
		if (MCOPT_ON && this.cadence$secondCurrent) cir.setReturnValue(this.cadence$view2);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void cadence$close(CallbackInfo ci) {
		if (this.cadence$view2 != null) {
			this.cadence$view2.close();
			this.cadence$texture2.close();
		}
	}
}
