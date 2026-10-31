package dev.cadence.shaders.cadence.mixin.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import java.util.function.Supplier;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cadence HDR (dev.cadence.metal.Hdr): the sun above SDR white. Its pipeline (CELESTIAL) blends additively, so drawing the quad
 * again adds its light again: with HDR on it is drawn {@link #DRAWS} times, about that many times as bright before the
 * present's tone mapping rolls it into the display's headroom. Off, or with a shader pack: drawn once, as vanilla.
 */
@Mixin(SkyRenderer.class)
public abstract class SkySunHdrMixin {
	private static final int DRAWS = 6;

	@Shadow
	private void drawCelestialBody(Supplier<String> label, RenderPass pass, GpuBufferSlice transform, GpuBuffer vertices, GpuBuffer indices, int count) {
	}

	@Redirect(method = "renderSun", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SkyRenderer;drawCelestialBody(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;I)V"))
	private void cadence$hdrSun(SkyRenderer self, Supplier<String> label, RenderPass pass, GpuBufferSlice transform, GpuBuffer vertices, GpuBuffer indices, int count) {
		// (not with a shader pack: its picture is shown as the pack made it, BH7-06)
		int n = dev.cadence.metal.Hdr.active() && dev.cadence.shaders.runtime.ShaderRuntime.get() == null ? DRAWS : 1;
		for (int i = 0; i < n; i++) this.drawCelestialBody(label, pass, transform, vertices, indices, count);
	}
}
